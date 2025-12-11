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
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        String playerIp = player.getIpAddress();

        // --- 1. CHECK ANTI-BOT: RETRASO TEMPORAL ---
        long joinTime = DirectAuth.getLoginManager().getConnectionTime(player);
        long secondsAlive = (System.currentTimeMillis() - joinTime) / 1000;
        int requiredDelay = DirectAuth.getConfig().registrationDelay;

        if (secondsAlive < requiredDelay) {
            player.sendSystemMessage(Component.literal("§cPlease wait a moment before registering."));
            return 0;
        }

        // --- 2. CHECK ANTI-BOT: LÍMITE DE IP ---
        int accountsOnIp = DirectAuth.getDatabase().countAccountsByIP(playerIp);
        
        if (accountsOnIp >= DirectAuth.getConfig().maxAccountsPerIP) {
            player.sendSystemMessage(Component.literal("§cRegistration limit reached for this IP address."));
            return 0;
        }
        
        // Verificar si ya está registrado
        if (DirectAuth.getDatabase().userExists(username)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyRegistered));
            return 0;
        }
        
        String password = StringArgumentType.getString(context, "password");
        
        // Validar contraseña
        if (password.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooShort));
            return 0;
        }
        
        if (password.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errPasswordTooLong));
            return 0;
        }
        
        // Crear usuario
        String hash = LoginManager.hashPassword(password);
        DirectAuth.getDatabase().createUserAsync(username, hash, playerIp);
        
        // Autenticar automáticamente
        DirectAuth.getLoginManager().setAuthenticated(player, true);
        
        // Restaurar posición original si existe (por si se movió al spawn al entrar)
        DirectAuth.getPositionManager().restorePosition(player);
        // Liberar ancla de restricción
        PlayerRestrictionHandler.removeAnchor(player);
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgRegistered));
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumEnableHint));
        
        return 1;
    }
}