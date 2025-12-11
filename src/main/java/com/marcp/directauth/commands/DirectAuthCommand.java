package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class DirectAuthCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("directauth");

        // --- Subcomando: CONFIRM (Para todos los usuarios) ---
        root.then(Commands.literal("confirm")
            .executes(context -> {
                if (context.getSource().getEntity() instanceof ServerPlayer player) {
                    ConfirmationManager.confirm(player);
                    return 1;
                }
                return 0;
            })
        );

        // --- Subcomandos: ADMIN (Requieren OP nivel 4) ---
        var adminNode = Commands.literal("admin") // Opcional: agrupar bajo 'admin' o dejarlo en la raíz
                .requires(source -> source.hasPermission(4));
        
        // 1. Online Mode Toggle (Antiguo PremiumAdmin)
        root.then(Commands.literal("online")
            .requires(s -> s.hasPermission(4))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(DirectAuthCommand::setOnlineMode)
                )
            )
        );

        // 2. Reset Password (Admin)
        root.then(Commands.literal("resetpass")
            .requires(s -> s.hasPermission(4))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("newPassword", StringArgumentType.word())
                    .executes(DirectAuthCommand::resetPassword)
                )
            )
        );

        // 3. Force Unregister (Admin)
        root.then(Commands.literal("unregister")
            .requires(s -> s.hasPermission(4))
            .then(Commands.argument("user", StringArgumentType.string())
                .executes(DirectAuthCommand::forceUnregister)
            )
        );

        dispatcher.register(root);
    }

    private static int setOnlineMode(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        boolean newValue = BoolArgumentType.getBool(context, "value");
        
        UserData userData = DirectAuth.getDatabase().getUser(username);
        if (userData == null) {
            context.getSource().sendFailure(Component.literal(String.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        userData.setPremium(newValue);
        if (!newValue) userData.setOnlineUUID(null); // Limpiar UUID si se desactiva
        
        DirectAuth.getDatabase().updateUser(username, userData);
        context.getSource().sendSuccess(() -> Component.literal(
            String.format(DirectAuth.getConfig().getLang().msgAdminPremiumUpdated, username, newValue)
        ), true);
        return 1;
    }

    private static int resetPassword(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        String newPass = StringArgumentType.getString(context, "newPassword");
        
        UserData userData = DirectAuth.getDatabase().getUser(username);
        if (userData == null) {
            context.getSource().sendFailure(Component.literal(String.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        userData.setPasswordHash(LoginManager.hashPassword(newPass));
        DirectAuth.getDatabase().updateUser(username, userData);
        
        context.getSource().sendSuccess(() -> Component.literal(
            String.format(DirectAuth.getConfig().getLang().msgAdminResetSuccess, username)
        ), true);
        return 1;
    }

    private static int forceUnregister(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        
        if (!DirectAuth.getDatabase().userExists(username)) {
            context.getSource().sendFailure(Component.literal(String.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        DirectAuth.getDatabase().deleteUser(username);
        
        // Si el jugador está online, lo echamos
        ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayerByName(username);
        if (player != null) {
            DirectAuth.getLoginManager().removePlayer(player);
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgAccountDeleted));
        }

        context.getSource().sendSuccess(() -> Component.literal(
            String.format(DirectAuth.getConfig().getLang().msgAdminUnregisterSuccess, username)
        ), true);
        return 1;
    }
}
