package com.marcp.directauth.auth;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import net.minecraft.server.level.ServerPlayer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado de autenticación de UNA instancia de servidor. Se crea una nueva en cada arranque
 * (ver {@link DirectAuth#resetLoginManager()}), así que no hay estado que pase de un mundo a otro.
 */
public class LoginManager {
    // Jugadores actualmente autenticados (UUID de la conexión)
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();

    // Intentos fallidos y cooldown por CUENTA + IP (no por UUID/conexión):
    // - sobreviven a reconexiones y agrupan las variantes de mayúsculas del mismo nombre
    //   ("Victim"/"VICTIM" tienen UUID offline distintos pero son la misma cuenta);
    // - al incluir la IP, un atacante no puede bloquear al dueño legítimo que entra desde otra.
    private final Map<String, FailedLogins> failedLogins = new ConcurrentHashMap<>();
    private final Map<String, Long> loginAttempts = new ConcurrentHashMap<>();

    // Los fallos cuentan durante al menos esta ventana (o loginLockoutSeconds si es mayor)
    private static final long FAILURE_WINDOW_MS = 15 * 60 * 1000L;
    // Tiempo que se conserva un dato pre-cargado si la conexión nunca llega a entrar al mundo
    private static final long PRELOAD_TTL_MS = 10 * 60 * 1000L;

    // Mapa para guardar el momento exacto de la conexión
    private final Map<UUID, Long> connectionTimes = new ConcurrentHashMap<>();

    // Caché temporal para pre-carga (Usuario -> Datos), rellenada por el mixin de login
    private final Map<String, PreLoaded> preLoginCache = new ConcurrentHashMap<>();

    private record PreLoaded(UserData data, long loadedAt) {}

    // Sesiones en periodo de gracia
    private final Map<UUID, GraceSession> graceSessions = new ConcurrentHashMap<>();

    // Jugadores con una operación de contraseña en curso (hash / DB en otro hilo)
    private final Set<UUID> pendingOperations = ConcurrentHashMap.newKeySet();

    // Mantenimiento (limpieza de sesiones) y hashing PBKDF2 fuera del hilo del servidor
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreads("DirectAuth-Maintenance"));
    private final ExecutorService hashExecutor = Executors.newFixedThreadPool(2, daemonThreads("DirectAuth-Hash"));

    // Referencia a la tarea de limpieza recurrente, para poder reprogramarla en un reload
    private ScheduledFuture<?> cleanupTask;

    private static final UserData NULL_MARKER = new UserData("NULL_MARKER", "");

    public LoginManager() {
        // Constructor vacío, la inicialización se hace en init()
    }

    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Inicializa (o reprograma) la tarea de limpieza con el intervalo configurado.
     * Es idempotente: cancela la tarea anterior antes de programar la nueva, así que
     * puede llamarse de forma segura tanto al arrancar como en cada /directauth reload.
     * Debe llamarse DESPUÉS de cargar la configuración.
     */
    public void init(int cleanupIntervalMinutes) {
        if (cleanupIntervalMinutes <= 0) cleanupIntervalMinutes = 10; // Fallback seguro

        // Cancelar la tarea previa para no apilar limpiezas recurrentes en cada reload
        if (cleanupTask != null) cleanupTask.cancel(false);

        // Ejecutar cada X minutos, con un delay inicial igual al intervalo
        cleanupTask = scheduler.scheduleAtFixedRate(this::cleanupExpiredEntries, cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);
    }

    // Configuración de PBKDF2
    private static final int ITERATIONS = 100000;
    // Iteraciones de DirectAuth 1.0.0, cuyos hashes (formato salt:hash) aún pueden estar en la DB
    private static final int LEGACY_ITERATIONS = 10000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Tarea de mantenimiento: Elimina sesiones y bloqueos caducados de la memoria.
     */
    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        graceSessions.values().removeIf(session -> now > session.expirationTime());
        failedLogins.values().removeIf(record -> windowExpired(record, now));
        long cooldown = DirectAuth.getConfig().loginCooldownMs;
        loginAttempts.values().removeIf(last -> now - last > cooldown);
        // Hellos de conexiones que nunca entraron al mundo (bots, rechazos, timeouts)
        preLoginCache.values().removeIf(entry -> now - entry.loadedAt() > PRELOAD_TTL_MS);
    }

    /**
     * Detiene los hilos del gestor. Se llama cuando el servidor se ha detenido.
     */
    public void shutdown() {
        scheduler.shutdownNow();
        hashExecutor.shutdown();
        try {
            if (!hashExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                hashExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            hashExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static String accountKey(ServerPlayer player) {
        return accountKey(player.getGameProfile().getName());
    }

    private static String accountKey(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public boolean isAuthenticated(ServerPlayer player) {
        return authenticatedPlayers.contains(player.getUUID());
    }

    public void setAuthenticated(ServerPlayer player, boolean authenticated) {
        if (authenticated) {
            authenticatedPlayers.add(player.getUUID());
        } else {
            authenticatedPlayers.remove(player.getUUID());
        }
    }

    /**
     * Limpia el estado de la CONEXIÓN. Los intentos fallidos y el cooldown son de la cuenta
     * y se conservan a propósito: si no, reconectar reiniciaría el límite de intentos.
     */
    public void removePlayer(ServerPlayer player) {
        authenticatedPlayers.remove(player.getUUID());
        connectionTimes.remove(player.getUUID());
        pendingOperations.remove(player.getUUID());
        preLoginCache.remove(accountKey(player)); // Limpiar también la caché al desconectar
    }

    /**
     * Se llama cuando el jugador se desconecta.
     * Guarda la IP y la hora de expiración en RAM.
     */
    public void pauseSession(ServerPlayer player) {
        // 1. Limpiamos el estado de la conexión (incluye quitarlo de autenticados)
        removePlayer(player);

        // 2. Calculamos expiración
        long durationSeconds = DirectAuth.getConfig().sessionGracePeriod;
        if (durationSeconds <= 0) return; // Si está desactivado (0), no guardamos nada

        long expiryTime = System.currentTimeMillis() + (durationSeconds * 1000);
        String ip = player.getIpAddress(); // NeoForge suele dar la IP sin puerto aquí

        // 3. Guardamos en el "Limbo"
        graceSessions.put(player.getUUID(), new GraceSession(accountKey(player), ip, expiryTime));
    }

    /**
     * Se llama cuando el jugador entra.
     * Intenta recuperar la sesión si la IP coincide y hay tiempo.
     */
    public boolean tryRestoreSession(ServerPlayer player) {
        UUID uuid = player.getUUID();
        GraceSession session = graceSessions.get(uuid);

        // Si no hay sesión guardada, nada que hacer
        if (session == null) return false;

        // 1. Chequeo de Tiempo
        if (System.currentTimeMillis() > session.expirationTime) {
            graceSessions.remove(uuid);
            return false; // Caducó
        }

        // 2. Chequeo de IP (CRÍTICO DE SEGURIDAD)
        // Si no coincide NO borramos la sesión: si no, cualquiera podría anular la del
        // dueño legítimo simplemente entrando con su nombre desde otra IP.
        String currentIp = player.getIpAddress();
        if (!session.ipAddress.equals(currentIp)) {
            DirectAuth.LOGGER.warn("DirectAuth: Invalid session attempt (different IP) for {}", player.getName().getString());
            return false;
        }

        // 3. Restaurar
        graceSessions.remove(uuid);
        setAuthenticated(player, true);
        return true;
    }

    public void invalidateSession(ServerPlayer player) {
        graceSessions.remove(player.getUUID());
    }

    /** Invalida todas las sesiones de gracia de una cuenta (todas las variantes de mayúsculas). */
    public void invalidateSessions(String username) {
        String key = accountKey(username);
        graceSessions.values().removeIf(session -> session.accountKey().equals(key));
    }

    // Clase interna simple para guardar los datos
    private record GraceSession(String accountKey, String ipAddress, long expirationTime) {}

    public void recordJoin(ServerPlayer player) {
        connectionTimes.put(player.getUUID(), System.currentTimeMillis());
    }

    public long getConnectionTime(ServerPlayer player) {
        return connectionTimes.getOrDefault(player.getUUID(), System.currentTimeMillis());
    }

    public boolean hasTimedOut(ServerPlayer player) {
        if (isAuthenticated(player)) return false; // Si ya está dentro, no hay timeout

        Long joinTime = connectionTimes.get(player.getUUID());
        if (joinTime == null) return false; // Por seguridad

        long elapsedSeconds = (System.currentTimeMillis() - joinTime) / 1000;
        return elapsedSeconds >= DirectAuth.getConfig().loginTimeout;
    }

    // --- Límite de intentos (por cuenta) ---

    private record FailedLogins(int count, long lastFailure) {}

    private static String failureKey(ServerPlayer player) {
        return accountKey(player) + "|" + player.getIpAddress();
    }

    private static long lockoutMs() {
        return Math.max(0, DirectAuth.getConfig().loginLockoutSeconds) * 1000L;
    }

    /** Los fallos antiguos dejan de contar. */
    private static boolean windowExpired(FailedLogins record, long now) {
        return now - record.lastFailure() > Math.max(FAILURE_WINDOW_MS, lockoutMs());
    }

    /** Ya cumplió el bloqueo tras llegar al máximo de intentos. */
    private static boolean lockExpired(FailedLogins record, long now) {
        return now - record.lastFailure() > lockoutMs();
    }

    private static boolean isStale(FailedLogins record, long now) {
        return windowExpired(record, now)
            || (record.count() >= DirectAuth.getConfig().maxLoginAttempts && lockExpired(record, now));
    }

    public boolean canAttemptLogin(ServerPlayer player) {
        Long lastAttempt = loginAttempts.get(failureKey(player));

        if (lastAttempt != null) {
            long elapsed = System.currentTimeMillis() - lastAttempt;
            return elapsed >= DirectAuth.getConfig().loginCooldownMs;
        }
        return true;
    }

    /** Marca el inicio de un intento de login (arranca el cooldown). */
    public void markLoginAttempt(ServerPlayer player) {
        loginAttempts.put(failureKey(player), System.currentTimeMillis());
    }

    public void recordLoginResult(ServerPlayer player, boolean success) {
        String key = failureKey(player);
        if (success) {
            failedLogins.remove(key);
            return;
        }
        long now = System.currentTimeMillis();
        failedLogins.compute(key, (k, previous) ->
            previous == null || isStale(previous, now)
                ? new FailedLogins(1, now)
                : new FailedLogins(previous.count() + 1, now));
    }

    /** Fallos que cuentan ahora mismo (0 si caducaron o si ya cumplió el bloqueo). */
    public int getFailedAttempts(ServerPlayer player) {
        FailedLogins record = failedLogins.get(failureKey(player));
        return record == null || isStale(record, System.currentTimeMillis()) ? 0 : record.count();
    }

    /** true mientras dure el bloqueo tras alcanzar maxLoginAttempts (desde esta IP). */
    public boolean hasExceededMaxAttempts(ServerPlayer player) {
        return getFailedAttempts(player) >= DirectAuth.getConfig().maxLoginAttempts;
    }

    /** Levanta el bloqueo por intentos de una cuenta desde todas las IPs (p. ej. tras resetpass). */
    public void clearFailedLogins(String username) {
        String prefix = accountKey(username) + "|";
        failedLogins.keySet().removeIf(key -> key.startsWith(prefix));
        loginAttempts.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // --- Operaciones asíncronas en curso (una por jugador) ---

    /** @return false si el jugador ya tiene una operación de contraseña en curso. */
    public boolean beginOperation(ServerPlayer player) {
        return pendingOperations.add(player.getUUID());
    }

    public void endOperation(UUID uuid) {
        pendingOperations.remove(uuid);
    }

    // MÉTODOS PARA LA PRE-CARGA
    public void addPreLoadedData(String username, UserData data) {
        // Guardamos el dato (incluso si es null, para saber que ya buscamos y no existe)
        if (username != null) {
            preLoginCache.put(accountKey(username), new PreLoaded(data != null ? data : NULL_MARKER, System.currentTimeMillis()));
        }
    }

    /** Devuelve el dato pre-cargado SIN quitarlo de la caché (null si no existe o no se cargó). */
    public UserData peekPreLoadedData(String username) {
        if (username == null) return null;
        PreLoaded entry = preLoginCache.get(accountKey(username));
        return entry == null || entry.data() == NULL_MARKER ? null : entry.data();
    }

    public UserData getAndRemovePreLoadedData(String username) {
        if (username == null) return null;
        PreLoaded entry = preLoginCache.remove(accountKey(username));

        // Si es el marcador de "no existe", devolvemos null real
        return entry == null || entry.data() == NULL_MARKER ? null : entry.data();
    }

    public boolean isPreLoaded(String username) {
        if (username == null) return false;
        return preLoginCache.containsKey(accountKey(username));
    }

    // --- Hashing con PBKDF2 (Nativo Java) ---

    public enum PasswordCheck {
        OK,
        /** Correcta, pero el hash usa un formato o nº de iteraciones antiguo y conviene regenerarlo. */
        OK_NEEDS_REHASH,
        WRONG;

        public boolean matches() {
            return this != WRONG;
        }
    }

    /** Hashea en el pool de hashing, fuera del hilo del servidor (cada hash cuesta ~50-150ms). */
    public CompletableFuture<String> hashPasswordAsync(String password) {
        return CompletableFuture.supplyAsync(() -> hashPassword(password), hashExecutor);
    }

    /** Verifica en el pool de hashing, fuera del hilo del servidor. */
    public CompletableFuture<PasswordCheck> verifyPasswordAsync(String password, String storedHash) {
        return CompletableFuture.supplyAsync(() -> verifyPassword(password, storedHash), hashExecutor);
    }

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS);

        // Formato: iteraciones:salt:hash (Base64)
        return ITERATIONS + ":" +
               Base64.getEncoder().encodeToString(salt) + ":" +
               Base64.getEncoder().encodeToString(hash);
    }

    public static PasswordCheck verifyPassword(String password, String storedHash) {
        if (storedHash == null) return PasswordCheck.WRONG;
        String[] parts = storedHash.split(":");
        try {
            if (parts.length == 3) {
                int iterations = Integer.parseInt(parts[0]);
                if (iterations <= 0) return PasswordCheck.WRONG;
                if (!matches(password, parts[1], parts[2], iterations)) return PasswordCheck.WRONG;
                return iterations == ITERATIONS ? PasswordCheck.OK : PasswordCheck.OK_NEEDS_REHASH;
            }
            if (parts.length == 2) {
                // Formato antiguo salt:hash sin iteraciones: 10k en la 1.0.0, 100k en las siguientes
                if (matches(password, parts[0], parts[1], ITERATIONS)
                        || matches(password, parts[0], parts[1], LEGACY_ITERATIONS)) {
                    return PasswordCheck.OK_NEEDS_REHASH;
                }
            }
        } catch (IllegalArgumentException e) {
            // Hash corrupto (Base64 o número inválido)
        }
        return PasswordCheck.WRONG;
    }

    private static boolean matches(String password, String saltB64, String hashB64, int iterations) {
        byte[] salt = Base64.getDecoder().decode(saltB64);
        byte[] originalHash = Base64.getDecoder().decode(hashB64);
        byte[] newHash = pbkdf2(password.toCharArray(), salt, iterations);
        // Comparación en tiempo constante
        return MessageDigest.isEqual(originalHash, newHash);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
