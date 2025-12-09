package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.UserData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PremiumCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("premium")
            .executes(PremiumCommand::execute)
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().errNotPlayer));
            return 0;
        }
        
        // Verificar autenticación
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errNotAuthenticated));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errUserNotFound));
            return 0;
        }
        
        // Verificar si ya es premium
        if (userData.isPremium()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgAlreadyPremium));
            return 0;
        }
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgVerifying));
        
        // Consultar API de forma asíncrona
        MojangAPI.getOnlineUUID(username).thenAccept(uuid -> {
            // Ejecutar en el hilo del servidor
            context.getSource().getServer().execute(() -> {
                if (uuid == null) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errMojangNotFound));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgMojangHint));
                } else {
                    String formattedUUID = MojangAPI.formatUUID(uuid);
                    // String playerUUID = player.getStringUUID(); // No usamos la UUID del jugador offline
                    
                    // Actualizar a premium
                    userData.setPremium(true);
                    userData.setOnlineUUID(formattedUUID); // Guardamos la UUID real de Mojang
                    DirectAuth.getDatabase().updateUser(username, userData);
                    
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgPremiumSuccess));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgAutoLoginHint));
                }
            });
        });
        
        return 1;
    }
}
