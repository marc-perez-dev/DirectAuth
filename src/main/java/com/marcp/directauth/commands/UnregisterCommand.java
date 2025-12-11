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

        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");
        UserData userData = DirectAuth.getDatabase().getUser(username);

        if (userData == null) return 0;

        // 1. Verificar contraseña
        if (!LoginManager.checkPassword(password, userData.getPasswordHash())) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errWrongPassword));
            return 0;
        }

        // 2. Solicitar Confirmación
        ConfirmationManager.requestConfirmation(player, () -> {
            DirectAuth.getDatabase().deleteUser(username);
            DirectAuth.getLoginManager().removePlayer(player); // Limpiar sesión
            
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgAccountDeleted));
        });

        return 1;
    }
}
