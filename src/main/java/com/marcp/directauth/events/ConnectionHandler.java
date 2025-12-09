package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
    public void onServerStopping(ServerStoppingEvent event) {
        if (DirectAuth.getDatabase() != null) {
            DirectAuth.getDatabase().close();
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Registrar tiempo de entrada
            DirectAuth.getLoginManager().recordJoin(player);
            String username = player.getGameProfile().getName();

            // 1. Intentamos obtener datos PRE-CARGADOS (Instanteo)
            UserData cachedData = DirectAuth.getLoginManager().getAndRemovePreLoadedData(username);

            if (cachedData != null || DirectAuth.getLoginManager().isPreLoaded(username)) { 
                // Si los datos ya estaban en caché (o el marcador de "no existe")
                processLogin(player, cachedData);
            } else {
                // 2. Si no dio tiempo a cargar (raro), hacemos el fallback asíncrono
                DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(userData -> {
                    processLogin(player, userData);
                }, player.getServer());
            }
        }
    }

    // Método auxiliar para no duplicar código
    private void processLogin(ServerPlayer player, UserData userData) {
        boolean isAuthenticated = false;

        // Caso Premium
        if (userData != null && userData.isPremium()) {
            String expectedUUID = userData.getOnlineUUID();
            String actualUUID = player.getStringUUID();
            
            if (expectedUUID != null && expectedUUID.equals(actualUUID)) {
                DirectAuth.getLoginManager().setAuthenticated(player, true);
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
                isAuthenticated = true;
            } else {
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumError));
                return;
            }
        }
        
        // Si NO está autenticado (usuario nuevo o login pendiente), ocultar coordenadas
        if (!isAuthenticated) {
            // [CAMBIO CLAVE] Solo guardar posición si está vivo y con salud
            if (!player.isDeadOrDying() && player.getHealth() > 0) {
                DirectAuth.getPositionManager().savePosition(player);
            }
            
            // Teletransportar al Spawn del Overworld
            ServerLevel overworld = player.getServer().overworld();
            BlockPos spawnPos = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
            
            if (userData == null) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgWelcome));
            } else {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgLoginRequest));
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumHint));
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