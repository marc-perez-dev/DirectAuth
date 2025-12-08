package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class RegisterCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("register")
            .then(Commands.argument("password", StringArgumentType.word())
                .executes(RegisterCommand::execute)
            )
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) {
        // En Brigadier, verificar la fuente es crucial
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().errNotPlayer));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        
        // Verificar si ya está registrado
        if (DirectAuth.getDatabase().userExists(username)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errAlreadyRegistered));
            return 0;
        }
        
        String password = StringArgumentType.getString(context, "password");
        
        // Validar contraseña
        if (password.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errPasswordTooShort));
            return 0;
        }
        
        if (password.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errPasswordTooLong));
            return 0;
        }
        
        // Crear usuario
        String hash = LoginManager.hashPassword(password);
        DirectAuth.getDatabase().createUser(username, hash);
        
        // Autenticar automáticamente
        DirectAuth.getLoginManager().setAuthenticated(player, true);
        
        // Restaurar posición original si existe (por si se movió al spawn al entrar)
        DirectAuth.getPositionManager().restorePosition(player);
        // Liberar ancla de restricción
        PlayerRestrictionHandler.removeAnchor(player);
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgRegistered));
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgPremiumEnableHint));
        
        return 1;
    }
}
