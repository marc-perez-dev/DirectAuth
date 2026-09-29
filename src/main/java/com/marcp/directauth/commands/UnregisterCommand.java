package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager.PasswordCheck;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class UnregisterCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("unregister")
            .then(Commands.argument("password", StringArgumentType.string())
                .executes(UnregisterCommand::execute)
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
        String password = StringArgumentType.getString(context, "password");

        if (!AuthTasks.begin(player)) return 0;

        // 1. Verificar contraseña (DB + PBKDF2 fuera del hilo del servidor). null = no hay cuenta
        CompletableFuture<PasswordCheck> check = DirectAuth.getDatabase().getUserAsync(username).thenCompose(user ->
            user == null
                ? CompletableFuture.completedFuture((PasswordCheck) null)
                : DirectAuth.getLoginManager().verifyPasswordAsync(password, user.getPasswordHash()));

        AuthTasks.finish(player, check, (p, result) -> {
            if (result == null) {
                p.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
                return;
            }
            if (!result.matches()) {
                p.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errWrongPasswordSimple));
                return;
            }

            // 2. Solicitar Confirmación
            ConfirmationManager.requestConfirmation(p, confirmer -> {
                if (!AuthTasks.begin(confirmer)) return;
                MinecraftServer server = confirmer.getServer();
                CompletableFuture<Boolean> deleted = DirectAuth.getDatabase().deleteUserAsync(username);

                // Fuera todas las conexiones y sesiones de la cuenta (incluida la de quien
                // confirmó), aunque este se haya desconectado mientras tanto
                deleted.thenAcceptAsync(existed -> {
                    if (existed) {
                        AuthTasks.revokeAccountAccess(server, username, null, DirectAuth.getConfig().getLang().msgAccountDeleted);
                    }
                }, AuthTasks.onServerThread(server));

                AuthTasks.finish(confirmer, deleted, (c, existed) -> {
                    if (!existed) {
                        c.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
                    }
                });
            });
        });

        return 1;
    }
}
