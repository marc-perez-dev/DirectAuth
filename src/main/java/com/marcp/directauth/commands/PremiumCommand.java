package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.MojangAPI;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class PremiumCommand {

    private enum Status { NOT_FOUND, ALREADY_PREMIUM, NEEDS_CONFIRMATION, WRONG_PASSWORD, MOJANG_UNAVAILABLE, NO_MOJANG_ACCOUNT, ENABLED }

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
        String currentUUID = player.getStringUUID();
        if (password != null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgVerifying));
        }

        CompletableFuture<Status> result = DirectAuth.getDatabase().getUserAsync(username).thenCompose(user -> {
            if (user == null) return CompletableFuture.completedFuture(Status.NOT_FOUND);
            if (user.isPremium()) return CompletableFuture.completedFuture(Status.ALREADY_PREMIUM);
            if (password == null) return CompletableFuture.completedFuture(Status.NEEDS_CONFIRMATION);

            return DirectAuth.getLoginManager().verifyPasswordAsync(password, user.getPasswordHash()).thenCompose(check -> {
                if (!check.matches()) return CompletableFuture.completedFuture(Status.WRONG_PASSWORD);
                return MojangAPI.getOnlineUUID(username).handle((uuid, error) -> {
                    if (error != null) {
                        DirectAuth.LOGGER.warn("DirectAuth: Error querying Mojang API for {}: {}", username, error.getMessage());
                        return CompletableFuture.completedFuture(Status.MOJANG_UNAVAILABLE);
                    }
                    String onlineUUID = MojangAPI.formatUUID(uuid);
                    if (onlineUUID == null) return CompletableFuture.completedFuture(Status.NO_MOJANG_ACCOUNT);
                    // La cuenta seguirá jugando con la UUID con la que juega (ver UserData)
                    String playerUUID = user.getPlayerUUID() != null ? user.getPlayerUUID() : currentUUID;
                    return DirectAuth.getDatabase().setOnlineModeAsync(username, true, onlineUUID, playerUUID)
                        .thenApply(updated -> updated ? Status.ENABLED : Status.NOT_FOUND);
                }).thenCompose(status -> status);
            });
        });

        AuthTasks.finish(player, result, PremiumCommand::onResult);
        return 1;
    }

    private static void onResult(ServerPlayer player, Status status) {
        var lang = DirectAuth.getConfig().getLang();
        switch (status) {
            case NOT_FOUND -> player.sendSystemMessage(Component.literal(lang.errUserNotFound));
            case ALREADY_PREMIUM -> player.sendSystemMessage(Component.literal(lang.msgAlreadyPremium));
            case NEEDS_CONFIRMATION -> {
                player.sendSystemMessage(Component.literal(lang.msgOnlineModeKeepsData));
                player.sendSystemMessage(Component.literal(lang.msgPremiumWarning));
            }
            case WRONG_PASSWORD -> player.sendSystemMessage(Component.literal(lang.errWrongPasswordSimple));
            case MOJANG_UNAVAILABLE -> player.sendSystemMessage(Component.literal(lang.errMojangUnavailable));
            case NO_MOJANG_ACCOUNT -> {
                player.sendSystemMessage(Component.literal(lang.errMojangNotFound));
                player.sendSystemMessage(Component.literal(lang.msgMojangHint));
            }
            // Sigue jugando: no cambia nada hasta su próxima entrada, ya con Mojang
            case ENABLED -> player.sendSystemMessage(Component.literal(lang.msgPremiumEnabled));
        }
    }
}
