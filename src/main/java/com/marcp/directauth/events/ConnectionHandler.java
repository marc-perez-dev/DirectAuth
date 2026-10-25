package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.MojangVerifiedConnection;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public class ConnectionHandler {

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Estado de autenticación limpio para esta instancia de servidor
        // (en singleplayer se arrancan varios servidores en la misma JVM)
        DirectAuth.resetLoginManager();

        // Inicializar la base de datos usando la ruta del nivel principal
        Path worldRoot = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        DirectAuth.initDatabase(worldRoot);
        // Inicializar configuración en world/serverconfig/DirectAuth-config.json
        DirectAuth.initConfig(worldRoot.resolve("serverconfig").resolve("DirectAuth-config.json"));

        // Inicializar el Scheduler del LoginManager con la configuración cargada
        DirectAuth.getLoginManager().init(DirectAuth.getConfig().sessionCleanupInterval);
    }

    /**
     * Se usa ServerStopped (no ServerStopping) porque los jugadores se desconectan DESPUÉS de
     * ServerStopping; así sus logouts se procesan antes de cerrar la DB y descartar el estado.
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        DirectAuth.closeDatabase();
        DirectAuth.getLoginManager().shutdown();
        PlayerRestrictionHandler.clearAnchors();
        ConfirmationManager.clear();
        JailTraffic.clear();
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Registrar tiempo de entrada
            DirectAuth.getLoginManager().recordJoin(player);
            String username = player.getGameProfile().getName();

            // 1. Intentamos obtener datos PRE-CARGADOS (Instantáneo). Hay que mirar si estaban
            // ANTES de sacarlos: el marcador de "no existe" se devuelve como null.
            boolean preLoaded = DirectAuth.getLoginManager().isPreLoaded(username);
            UserData cachedData = DirectAuth.getLoginManager().getAndRemovePreLoadedData(username);
            // Ahora, antes de que tickee: el tiempo de juego sube en la jaula y, con el fallback
            // asíncrono, un jugador nuevo parecería haber jugado ya
            boolean playedBefore = hasPlayedBefore(player);

            if (preLoaded) {
                processLogin(player, cachedData, playedBefore);
            } else {
                // 2. Si no dio tiempo a cargar (raro), hacemos el fallback asíncrono
                UUID uuid = player.getUUID();
                ServerGamePacketListenerImpl connection = player.connection;
                DirectAuth.getDatabase().getUserAsync(username).whenCompleteAsync((userData, error) -> {
                    ServerPlayer current = player.getServer().getPlayerList().getPlayer(uuid);
                    if (current == null || current.connection != connection) return; // Se fue mientras tanto
                    if (error != null) {
                        DirectAuth.LOGGER.error("DirectAuth: Could not load account data for {}", username, error);
                        current.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errInternal));
                        return;
                    }
                    processLogin(current, userData, playedBefore);
                }, AuthTasks.onServerThread(player.getServer()));
            }
        }
    }

    // Método auxiliar para no duplicar código
    private void processLogin(ServerPlayer player, UserData userData, boolean playedBefore) {
        boolean isAuthenticated = false;

        // Caso Premium: Mojang tiene que haber confirmado ESTA cuenta en el login. La UUID del
        // jugador no lo demuestra: la cuenta juega con la que ya tenía antes del Modo Online.
        boolean premium = userData != null && userData.isPremium();
        if (premium) {
            UUID verifiedUUID = ((MojangVerifiedConnection) player.connection.getConnection()).directAuth$getMojangUUID();
            if (verifiedUUID == null || !verifiedUUID.toString().equalsIgnoreCase(userData.getOnlineUUID())) {
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgPremiumError));
                return;
            }
        }

        // El login tiene que haberle dado la UUID de la cuenta (MixinServerLoginPacketListenerImpl).
        // Si no (sus datos se descartaron a mitad del login), las mods lo tratarían como un jugador
        // nuevo con esa otra UUID: mejor que vuelva a entrar.
        String accountUUID = userData != null ? userData.getAccountUUID() : null;
        if (accountUUID != null && !accountUUID.equalsIgnoreCase(player.getStringUUID())
                && !player.getServer().usesAuthentication() && !player.getServer().isSingleplayerOwner(player.getGameProfile())) {
            DirectAuth.LOGGER.warn("DirectAuth: {} joined as {} instead of the account's UUID {}; disconnecting", player.getGameProfile().getName(), player.getStringUUID(), accountUUID);
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errInternal));
            return;
        }

        if (premium) {
            DirectAuth.getLoginManager().setAuthenticated(player, true);
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAutoLogin));
            isAuthenticated = true;
        }

        // INTENTO DE RESTAURACIÓN DE SESIÓN (solo si la cuenta sigue existiendo)
        if (!isAuthenticated && userData != null) {
            if (DirectAuth.getLoginManager().tryRestoreSession(player)) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgSessionRestored));
                isAuthenticated = true;
            }
        }

        if (isAuthenticated) {
            // Si una entrada anterior sin login (p. ej. alguien con su nombre desde otra IP) lo
            // movió al spawn, su posición real quedó guardada: devolverlo a ella.
            DirectAuth.getPositionManager().restorePosition(player);
            // Como tras /login: lo que otras mods le enviaron (o él a ellas) en este mismo evento
            // de entrada, antes de que DirectAuth lo autenticara
            PlayerRestrictionHandler.release(player);
        }

        // Si NO está autenticado (usuario nuevo o login pendiente), ocultar coordenadas
        if (!isAuthenticated) {
            // Un jugador que nunca ha jugado no tiene una posición que ocultar: se queda donde lo
            // haya puesto el juego o una mod que lo mueve en la primera entrada (a otra dimensión, a
            // una isla...), corra antes o después que DirectAuth.

            // [CAMBIO CLAVE] Solo guardar posición si está vivo y con salud. Un jugador que nunca
            // ha jugado ya está en el spawn: no hace falta guardarle nada (y así los bots con
            // nombres aleatorios no hacen crecer el fichero de posiciones).
            if (!player.isDeadOrDying() && player.getHealth() > 0 && playedBefore) {
                DirectAuth.getPositionManager().savePosition(player);
            }

            // Teletransportar al Spawn del Overworld para no revelar dónde estaba
            if (playedBefore) {
                ServerLevel overworld = player.getServer().overworld();
                BlockPos spawnPos = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
            }

            // Si el jugador ya tickeó antes de llegar aquí (fallback asíncrono), su ancla apunta
            // a la posición original: la descartamos para que se re-ancle donde está ahora.
            PlayerRestrictionHandler.removeAnchor(player);

            // El mixin de tickEffects congela las duraciones en servidor. Limpiamos
            // el HUD del cliente para que no muestre cuentas atrás fantasma; el
            // estado real persiste y se resincronizará al autenticar.
            PlayerRestrictionHandler.hideEffectsFromClient(player);

            if (userData == null) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgWelcome));
            } else {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgLoginRequest));
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumHint));
            }
        }
    }

    private static boolean hasPlayedBefore(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) > 0;
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // SI estaba autenticado, guardamos la sesión temporal
            if (DirectAuth.getLoginManager().isAuthenticated(player)) {
                DirectAuth.getLoginManager().pauseSession(player);
            } else {
                // Si no estaba logueado, limpieza normal
                DirectAuth.getLoginManager().removePlayer(player);
            }

            PlayerRestrictionHandler.removeAnchor(player);
            ConfirmationManager.cancel(player);
            // Lo retenido de una conexión que no llegó a autenticarse no se procesa nunca
            JailTraffic.discard(player);
            // Nota: No borramos la posición guardada aquí. Se mantiene hasta que se autentique correctamente.
        }
    }
}
