package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConfirmationManager {
    private static final Map<UUID, PendingAction> pendingActions = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 30000; // 30 segundos para confirmar

    private record PendingAction(Runnable action, long timestamp) {}

    public static void requestConfirmation(ServerPlayer player, Runnable action) {
        pendingActions.put(player.getUUID(), new PendingAction(action, System.currentTimeMillis()));
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
        pending.action.run();
    }
}
