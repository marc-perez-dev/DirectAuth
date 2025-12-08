package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class LoginCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.greedyString())
                .executes(LoginCommand::execute)
            )
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().errNotPlayer));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        // Verificar si está registrado
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errNotRegistered));
            return 0;
        }
        
        // Verificar si ya está autenticado
        if (DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errAlreadyAuthenticated));
            return 0;
        }
        
        // Verificar cooldown
        if (!DirectAuth.getLoginManager().canAttemptLogin(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errCooldown));
            return 0;
        }
        
        // Verificar si excedió intentos
        if (DirectAuth.getLoginManager().hasExceededMaxAttempts(player)) {
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().errMaxAttempts));
            return 0;
        }
        
        String password = StringArgumentType.getString(context, "password");
        
        // Verificar contraseña
        if (LoginManager.checkPassword(password, userData.getPasswordHash())) {
            DirectAuth.getLoginManager().setAuthenticated(player, true);
            DirectAuth.getLoginManager().recordLoginAttempt(player, true);
            
            // Restaurar posición original si existe
            DirectAuth.getPositionManager().restorePosition(player);
            // Liberar ancla de restricción
            PlayerRestrictionHandler.removeAnchor(player);
            
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgAuthenticated));
            return 1;
        } else {
            DirectAuth.getLoginManager().recordLoginAttempt(player, false);
            int attempts = DirectAuth.getLoginManager().getFailedAttempts(player);
            player.sendSystemMessage(Component.literal(String.format(DirectAuth.getConfig().errWrongPassword, attempts, DirectAuth.getConfig().maxLoginAttempts)));
            return 0;
        }
    }
}
