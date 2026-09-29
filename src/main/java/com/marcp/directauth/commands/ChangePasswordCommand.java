package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager.PasswordCheck;
import com.marcp.directauth.config.LangConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ChangePasswordCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("changepassword")
            .then(Commands.argument("oldPassword", StringArgumentType.string())
                .then(Commands.argument("newPassword", StringArgumentType.word())
                    .executes(ChangePasswordCommand::execute)
                )
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return 0;

        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String oldPass = StringArgumentType.getString(context, "oldPassword");
        String newPass = StringArgumentType.getString(context, "newPassword");

        // 1. Validar longitud nueva
        if (newPass.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errPasswordTooShort, DirectAuth.getConfig().minPasswordLength)));
            return 0;
        }
        if (newPass.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errPasswordTooLong, DirectAuth.getConfig().maxPasswordLength)));
            return 0;
        }

        if (!AuthTasks.begin(player)) return 0;

        // 2. Validar contraseña antigua (DB + PBKDF2 fuera del hilo del servidor).
        // null = la cuenta ya no existe
        CompletableFuture<PasswordCheck> check = DirectAuth.getDatabase().getUserAsync(username).thenCompose(user ->
            user == null
                ? CompletableFuture.completedFuture((PasswordCheck) null)
                : DirectAuth.getLoginManager().verifyPasswordAsync(oldPass, user.getPasswordHash()));

        AuthTasks.finish(player, check, (p, result) -> {
            if (result == null) {
                p.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
                return;
            }
            if (!result.matches()) {
                p.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errOldPasswordWrong));
                return;
            }

            // 3. Solicitar Confirmación
            // Solo cambia la contraseña: el Modo Online se conserva. Desactivarlo aquí dejaría
            // los datos del jugador bajo la UUID online sin migrarlos de vuelta.
            ConfirmationManager.requestConfirmation(p, confirmer -> {
                if (!AuthTasks.begin(confirmer)) return;
                MinecraftServer server = confirmer.getServer();
                UUID confirmerId = confirmer.getUUID();
                CompletableFuture<Boolean> updated = DirectAuth.getLoginManager().hashPasswordAsync(newPass)
                    .thenCompose(newHash -> DirectAuth.getDatabase().updatePasswordAsync(username, newHash));

                // La contraseña anterior deja de dar acceso: sesiones de gracia fuera y expulsadas
                // las demás conexiones de la cuenta (otras variantes de mayúsculas). Se hace
                // aunque quien confirmó se haya desconectado mientras tanto.
                updated.thenAcceptAsync(exists -> {
                    if (exists) {
                        AuthTasks.revokeAccountAccess(server, username, server.getPlayerList().getPlayer(confirmerId),
                            DirectAuth.getConfig().getLang().msgPasswordChangedElsewhere);
                    }
                }, AuthTasks.onServerThread(server));

                AuthTasks.finish(confirmer, updated, (c, exists) -> {
                    if (!exists) {
                        c.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
                        return;
                    }
                    c.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPasswordChanged));
                });
            });
        });

        return 1;
    }
}
