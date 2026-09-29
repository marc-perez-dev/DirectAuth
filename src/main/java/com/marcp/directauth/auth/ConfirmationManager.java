package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ConfirmationManager {
    private static final Map<UUID, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 30000; // 30 segundos para confirmar

    /** La acción recibe el jugador que confirma (la entidad puede haber cambiado tras un respawn). */
    private record PendingAction(Consumer<ServerPlayer> action, long timestamp) {}

    public static void requestConfirmation(ServerPlayer player, Consumer<ServerPlayer> action) {
        long now = System.currentTimeMillis();
        pendingActions.values().removeIf(pending -> now - pending.timestamp > TIMEOUT_MS);
        pendingActions.put(player.getUUID(), new PendingAction(action, now));
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgConfirmRequest));
    }

    public static void confirm(ServerPlayer player) {
        PendingAction pending = pendingActions.remove(player.getUUID());

        if (pending == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNoPendingAction));
            return;
        }

        if (System.currentTimeMillis() - pending.timestamp > TIMEOUT_MS) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgActionExpired));
            return;
        }

        // Ejecutar la acción
        pending.action.accept(player);
    }

    /** Descarta la acción pendiente de un jugador (al desconectarse). */
    public static void cancel(ServerPlayer player) {
        pendingActions.remove(player.getUUID());
    }

    public static void clear() {
        pendingActions.clear();
    }
}
