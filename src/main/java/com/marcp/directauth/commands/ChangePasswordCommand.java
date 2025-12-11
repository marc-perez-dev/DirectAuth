package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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

        // 1. Validar contraseña antigua
        UserData userData = DirectAuth.getDatabase().getUser(username);
        if (!LoginManager.checkPassword(oldPass, userData.getPasswordHash())) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errOldPasswordWrong));
            return 0;
        }

        // 2. Validar longitud nueva
        if (newPass.length() < DirectAuth.getConfig().minPasswordLength || 
            newPass.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooShort));
            return 0;
        }

        // 3. Solicitar Confirmación
        ConfirmationManager.requestConfirmation(player, () -> {
            String newHash = LoginManager.hashPassword(newPass);
            userData.setPasswordHash(newHash);
            // Si cambian la pass, desactivamos premium para evitar inconsistencias
            userData.setPremium(false); 
            userData.setOnlineUUID(null);
            
            DirectAuth.getDatabase().updateUserAsync(username, userData);
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPasswordChanged));
        });

        return 1;
    }
}
