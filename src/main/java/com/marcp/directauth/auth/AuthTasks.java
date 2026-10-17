package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.rcon.RconConsoleSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Utilidades para comandos cuyo trabajo pesado (PBKDF2, SQLite, Mojang) se hace fuera del hilo
 * del servidor. Los resultados siempre vuelven al hilo del servidor antes de tocar al jugador.
 */
public final class AuthTasks {
    private AuthTasks() {}

    /**
     * Executor que ejecuta las tareas en el hilo del servidor, o las DESCARTA si el servidor ya
     * se está deteniendo: en ese caso {@code server.execute} ejecutaría la tarea en el hilo que
     * completó el future (DB, hash, HTTP), en paralelo con el guardado de jugadores y chunks.
     */
    public static Executor onServerThread(MinecraftServer server) {
        return task -> server.execute(() -> {
            if (server.isSameThread()) task.run();
        });
    }

    /**
     * Reserva la única operación en curso permitida por jugador.
     * @return false (y avisa al jugador) si ya tenía una en curso.
     */
    public static boolean begin(ServerPlayer player) {
        if (DirectAuth.getLoginManager().beginOperation(player)) return true;
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errOperationPending));
        return false;
    }

    /**
     * Cuando {@code future} termina, libera la operación de {@link #begin} y ejecuta
     * {@code onSuccess} en el hilo del servidor con la entidad ACTUAL del jugador
     * (puede haber reaparecido). Si se desconectó mientras tanto (aunque haya vuelto a entrar
     * con otra conexión), no hace nada.
     */
    public static <T> void finish(ServerPlayer player, CompletableFuture<T> future, BiConsumer<ServerPlayer, T> onSuccess) {
        MinecraftServer server = player.getServer();
        UUID uuid = player.getUUID();
        ServerGamePacketListenerImpl connection = player.connection;
        LoginManager loginManager = DirectAuth.getLoginManager();
        future.whenCompleteAsync((result, error) -> {
            ServerPlayer current = server.getPlayerList().getPlayer(uuid);
            if (current == null || current.connection != connection) return;
            loginManager.endOperation(uuid);
            if (error != null) {
                DirectAuth.LOGGER.error("DirectAuth: Operation failed for {}", current.getGameProfile().getName(), error);
                current.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errInternal));
                return;
            }
            try {
                onSuccess.accept(current, result);
            } catch (RuntimeException e) {
                DirectAuth.LOGGER.error("DirectAuth: Operation failed for {}", current.getGameProfile().getName(), e);
                current.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errInternal));
            }
        }, onServerThread(server));
    }

    /**
     * Igual que {@link #finish} pero para comandos de admin (la fuente puede ser la consola o RCON).
     * Si la fuente es RCON, la respuesta espera a que termine la operación (ver
     * {@link #awaitRconOperations}); si no, el resultado se perdería.
     */
    public static <T> void finish(CommandSourceStack source, CompletableFuture<T> future, Consumer<T> onSuccess) {
        Object rconToken = source.source instanceof RconConsoleSource ? rconOperationStarted() : null;
        future.whenCompleteAsync((result, error) -> {
            try {
                Throwable failure = error;
                if (failure == null) {
                    try {
                        onSuccess.accept(result);
                    } catch (RuntimeException e) {
                        failure = e;
                    }
                }
                if (failure != null) {
                    DirectAuth.LOGGER.error("DirectAuth: Admin operation failed", failure);
                    source.sendFailure(Component.literal(DirectAuth.getConfig().getLang().errInternal));
                }
            } finally {
                // Después del callback: si encadenó otra operación, esa ya está registrada
                if (rconToken != null) rconOperationFinished(rconToken);
            }
        }, onServerThread(source.getServer()));
    }

    // --- Respuesta de RCON ---
    // Vanilla ejecuta el comando RCON en el hilo del servidor y lee el búfer de respuesta en
    // cuanto el comando retorna, antes de que terminen las operaciones asíncronas. Por eso el
    // hilo de RCON (no el del servidor) espera a las operaciones que lanzó el comando.

    /** Lo que espera una respuesta de RCON como máximo (cubre la consulta a Mojang). */
    private static final long RCON_WAIT_MS = 30_000;
    private static final Set<Object> rconPending = new HashSet<>(); // protegido por sí mismo

    private static Object rconOperationStarted() {
        Object token = new Object();
        synchronized (rconPending) {
            rconPending.add(token);
        }
        return token;
    }

    private static void rconOperationFinished(Object token) {
        synchronized (rconPending) {
            rconPending.remove(token);
            rconPending.notifyAll();
        }
    }

    /** Al empezar cada comando RCON: las operaciones de comandos anteriores ya no se esperan. */
    public static void resetRconOperations() {
        synchronized (rconPending) {
            rconPending.clear();
            rconPending.notifyAll();
        }
    }

    /**
     * Bloquea el hilo de RCON hasta que terminen las operaciones lanzadas por el comando actual
     * (o hasta {@link #RCON_WAIT_MS}). NUNCA llamar desde el hilo del servidor: es el que las completa.
     */
    public static void awaitRconOperations() {
        long deadline = System.currentTimeMillis() + RCON_WAIT_MS;
        synchronized (rconPending) {
            while (!rconPending.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    DirectAuth.LOGGER.warn("DirectAuth: RCON response sent before the command finished; its result will only appear in the server log");
                    return;
                }
                try {
                    rconPending.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Jugadores conectados con esta cuenta (todas las variantes de mayúsculas del nombre). */
    public static List<ServerPlayer> playersOfAccount(MinecraftServer server, String username) {
        List<ServerPlayer> result = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(username)) {
                result.add(player);
            }
        }
        return result;
    }

    /**
     * Revoca el acceso actual a una cuenta: invalida sus sesiones de gracia y expulsa a todas
     * las conexiones con ese nombre (en cualquier combinación de mayúsculas), salvo {@code keep}.
     * Llamar en el hilo del servidor.
     */
    public static void revokeAccountAccess(MinecraftServer server, String username, ServerPlayer keep, String kickMessage) {
        LoginManager loginManager = DirectAuth.getLoginManager();
        loginManager.invalidateSessions(username);
        for (ServerPlayer player : playersOfAccount(server, username)) {
            if (player == keep) continue;
            // Quitar la autenticación ANTES de desconectar, para que el logout no cree una
            // sesión de gracia nueva
            loginManager.removePlayer(player);
            player.connection.disconnect(Component.literal(kickMessage));
        }
    }
}
