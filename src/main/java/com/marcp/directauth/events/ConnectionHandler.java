package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public class ConnectionHandler {
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Inicializar la base de datos usando la ruta del nivel principal
        Path worldRoot = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        DirectAuth.initDatabase(worldRoot);
        // Inicializar configuración en world/serverconfig/DirectAuth-config.json
        DirectAuth.initConfig(worldRoot.resolve("serverconfig").resolve("DirectAuth-config.json"));
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String username = player.getGameProfile().getName();
            UserData userData = DirectAuth.getDatabase().getUser(username);
            
            boolean isAuthenticated = false;

            // Caso 3: Usuario premium - verificar UUID
            if (userData != null && userData.isPremium()) {
                String expectedUUID = userData.getOnlineUUID();
                String actualUUID = player.getStringUUID();
                
                if (expectedUUID != null && expectedUUID.equals(actualUUID)) {
                    // Auto-login exitoso
                    DirectAuth.getLoginManager().setAuthenticated(player, true);
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgAutoLogin));
                    isAuthenticated = true;
                } else {
                    player.connection.disconnect(Component.literal(DirectAuth.getConfig().msgPremiumError));
                    return; // Importante: salir si es kickeado
                }
            }
            
            // Si NO está autenticado (usuario nuevo o login pendiente), ocultar coordenadas
            if (!isAuthenticated) {
                // Guardar posición real
                DirectAuth.getPositionManager().savePosition(player);
                
                // Teletransportar al Spawn del Overworld
                ServerLevel overworld = player.getServer().overworld();
                BlockPos spawnPos = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
                
                if (userData == null) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgWelcome));
                } else {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgLoginRequest));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgPremiumHint));
                }
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DirectAuth.getLoginManager().removePlayer(player);
            PlayerRestrictionHandler.removeAnchor(player);
            // Nota: No borramos la posición guardada aquí. Se mantiene hasta que se autentique correctamente.
        }
    }
}
