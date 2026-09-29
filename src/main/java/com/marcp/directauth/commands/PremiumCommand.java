package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AccountModeSwitch;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class PremiumCommand {

    private enum Status { NOT_FOUND, ALREADY_PREMIUM, NEEDS_CONFIRMATION, WRONG_PASSWORD, MOJANG_UNAVAILABLE, NO_MOJANG_ACCOUNT, VERIFIED }

    /** Resultado de la parte asíncrona (DB, PBKDF2 y Mojang). */
    private record Verification(Status status, UserData user, String onlineUUID) {
        static CompletableFuture<Verification> of(Status status) {
            return CompletableFuture.completedFuture(new Verification(status, null, null));
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("online")
            .executes(context -> execute(context, null))
            .then(Commands.argument("password", StringArgumentType.string())
                .executes(context -> execute(context, StringArgumentType.getString(context, "password")))
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context, String password) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }

        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }

        if (!AuthTasks.begin(player)) return 0;

        String username = player.getGameProfile().getName();
        if (password != null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgVerifying));
        }

        CompletableFuture<Verification> verification = DirectAuth.getDatabase().getUserAsync(username).thenCompose(user -> {
            if (user == null) return Verification.of(Status.NOT_FOUND);
            if (user.isPremium()) return Verification.of(Status.ALREADY_PREMIUM);
            if (password == null) return Verification.of(Status.NEEDS_CONFIRMATION);

            return DirectAuth.getLoginManager().verifyPasswordAsync(password, user.getPasswordHash()).thenCompose(check -> {
                if (!check.matches()) return Verification.of(Status.WRONG_PASSWORD);
                return MojangAPI.getOnlineUUID(username).handle((uuid, error) -> {
                    if (error != null) {
                        DirectAuth.LOGGER.warn("DirectAuth: Error querying Mojang API for {}: {}", username, error.getMessage());
                        return new Verification(Status.MOJANG_UNAVAILABLE, null, null);
                    }
                    String formatted = MojangAPI.formatUUID(uuid);
                    return formatted == null
                        ? new Verification(Status.NO_MOJANG_ACCOUNT, null, null)
                        : new Verification(Status.VERIFIED, user, formatted);
                });
            });
        });

        AuthTasks.finish(player, verification, PremiumCommand::onVerification);
        return 1;
    }

    private static void onVerification(ServerPlayer player, Verification verification) {
        var lang = DirectAuth.getConfig().getLang();
        switch (verification.status()) {
            case NOT_FOUND -> player.sendSystemMessage(Component.literal(lang.errUserNotFound));
            case ALREADY_PREMIUM -> player.sendSystemMessage(Component.literal(lang.msgAlreadyPremium));
            case NEEDS_CONFIRMATION -> {
                player.sendSystemMessage(Component.literal(lang.msgOnlineModeWarning));
                player.sendSystemMessage(Component.literal(lang.msgPremiumWarning));
            }
            case WRONG_PASSWORD -> player.sendSystemMessage(Component.literal(lang.errWrongPasswordSimple));
            case MOJANG_UNAVAILABLE -> player.sendSystemMessage(Component.literal(lang.errMojangUnavailable));
            case NO_MOJANG_ACCOUNT -> {
                player.sendSystemMessage(Component.literal(lang.errMojangNotFound));
                player.sendSystemMessage(Component.literal(lang.msgMojangHint));
            }
            case VERIFIED -> {
                // Guardar, migrar y expulsar ocurren en esta llamada (sin ventana para seguir
                // jugando con los ficheros ya movidos). Si la migración falla, el future ya está
                // completado con false y el jugador sigue conectado.
                String username = verification.user().getUsername();
                AccountModeSwitch.apply(player, verification.user(), true, verification.onlineUUID(), lang.msgPremiumKick)
                    .whenComplete((switched, error) -> {
                        if (error != null) {
                            DirectAuth.LOGGER.error("DirectAuth: Online Mode for {} was not saved; the player keeps offline mode", username, error);
                        } else if (!switched) {
                            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPremiumMigrationFailed));
                        }
                    });
            }
        }
    }
}
