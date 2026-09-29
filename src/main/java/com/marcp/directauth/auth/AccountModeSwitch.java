package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.MigrationManager;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.mixin.PlayerListAccessor;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Cambia una cuenta entre Modo Online (UUID de Mojang) y offline, moviendo los datos de jugador
 * de una UUID a otra. O se aplica todo (ficheros + base de datos) o nada.
 */
public final class AccountModeSwitch {
    private AccountModeSwitch() {}

    /**
     * Cambia el modo de un jugador CONECTADO (su UUID actual es el origen de la migración).
     * Debe llamarse en el hilo del servidor. Guardar, migrar y expulsar ocurren en esta misma
     * llamada, así que el jugador no puede seguir jugando con los ficheros ya movidos (lo que
     * permitiría duplicar objetos). La base de datos se actualiza después; si eso falla, la
     * migración se revierte y al volver a entrar sigue en el modo anterior.
     *
     * @param onlineUUID UUID de Mojang con guiones; obligatoria si {@code premium} es true.
     * @return future con false si la migración de ficheros falló y se revirtió (el jugador NO se
     *         expulsa y la cuenta no cambia; se completa en esta misma llamada), o true cuando la
     *         base de datos está actualizada. Se completa con excepción si falló la base de datos.
     */
    public static CompletableFuture<Boolean> apply(ServerPlayer player, UserData user, boolean premium, String onlineUUID, String kickMessage) {
        MinecraftServer server = player.getServer();
        String fromUUID = player.getStringUUID();
        String toUUID;
        String offlineUUID;
        if (premium) {
            toUUID = onlineUUID;
            offlineUUID = fromUUID; // Recordamos la UUID exacta para poder volver
        } else {
            // La UUID offline depende de las mayúsculas exactas del nombre con el que jugaba en
            // offline; si la guardamos al activar premium, usamos esa.
            toUUID = user.getOfflineUUID() != null
                ? user.getOfflineUUID()
                : UUIDUtil.createOfflinePlayerUUID(player.getGameProfile().getName()).toString();
            offlineUUID = null;
        }
        if (toUUID == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Missing online UUID"));
        }

        // Guardar datos actuales del jugador antes de migrar
        ((PlayerListAccessor) server.getPlayerList()).callSave(player);

        MigrationManager.Migration migration;
        try {
            migration = MigrationManager.migratePlayerData(server, fromUUID, toUUID);
        } catch (IOException e) {
            // MigrationManager ya ha revertido los pasos aplicados
            DirectAuth.LOGGER.error("DirectAuth: Error migrating data for {}", user.getUsername(), e);
            return CompletableFuture.completedFuture(false);
        }

        // Expulsar YA (disconnect elimina al jugador en esta misma tarea del hilo del servidor)
        player.connection.disconnect(Component.literal(kickMessage));

        return persist(user.getUsername(), premium, premium ? onlineUUID : null, offlineUUID, migration);
    }

    /**
     * Desactiva el Modo Online de una cuenta cuyo jugador NO está conectado. Solo es posible si
     * se conoce la UUID offline exacta (cuentas convertidas con esta versión o posteriores).
     *
     * @return igual que {@link #apply}.
     */
    public static CompletableFuture<Boolean> disableWhileOffline(MinecraftServer server, UserData user) {
        if (user.getOnlineUUID() == null || user.getOfflineUUID() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Offline UUID unknown"));
        }
        MigrationManager.Migration migration;
        try {
            migration = MigrationManager.migratePlayerData(server, user.getOnlineUUID(), user.getOfflineUUID());
        } catch (IOException e) {
            DirectAuth.LOGGER.error("DirectAuth: Error migrating data for {}", user.getUsername(), e);
            return CompletableFuture.completedFuture(false);
        }
        return persist(user.getUsername(), false, null, null, migration);
    }

    private static CompletableFuture<Boolean> persist(String username, boolean premium, String onlineUUID, String offlineUUID, MigrationManager.Migration migration) {
        // Solo las columnas del modo: no pisa un cambio de contraseña hecho mientras tanto
        return DirectAuth.getDatabase().setOnlineModeAsync(username, premium, onlineUUID, offlineUUID).handle((updated, error) -> {
            if (error != null || !Boolean.TRUE.equals(updated)) {
                // Sin el cambio en la DB, los datos migrados no se encontrarían al volver a entrar.
                // (Operaciones de ficheros: se pueden hacer desde el hilo de la DB.)
                migration.rollback();
                DirectAuth.LOGGER.error("DirectAuth: Could not save the Online Mode change for {}; the data migration was rolled back", username, error);
                throw new CompletionException(error != null ? error : new IllegalStateException("Account " + username + " no longer exists"));
            }
            return true;
        });
    }
}
