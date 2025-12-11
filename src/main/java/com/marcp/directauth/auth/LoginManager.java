package com.marcp.directauth.auth;

import net.minecraft.server.level.ServerPlayer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.marcp.directauth.data.UserData; // Asegúrate de importar esto

public class LoginManager {
    // Jugadores actualmente autenticados (UUID offline -> true)
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    
    // Cooldown de intentos fallidos (UUID -> timestamp del último intento)
    private final Map<UUID, Long> loginAttempts = new ConcurrentHashMap<>();
    
    // Contador de intentos fallidos (UUID -> contador)
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();

    // Mapa para guardar el momento exacto de la conexión
    private final Map<UUID, Long> connectionTimes = new ConcurrentHashMap<>();

    // NUEVO: Caché temporal para pre-carga (Usuario -> Datos)
    private final Map<String, UserData> preLoginCache = new ConcurrentHashMap<>();

    // NUEVO: Mapa para sesiones en periodo de gracia
    private final Map<UUID, GraceSession> graceSessions = new ConcurrentHashMap<>();
    
    private static final long COOLDOWN_MS = 3000; // 3 segundos entre intentos
    private static final int MAX_ATTEMPTS = 5; // Máximo 5 intentos antes de kick
    
    // Configuración de PBKDF2
    private static final int ITERATIONS = 100000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    public boolean isAuthenticated(ServerPlayer player) {
        return authenticatedPlayers.contains(player.getUUID());
    }
    
    public void setAuthenticated(ServerPlayer player, boolean authenticated) {
        if (authenticated) {
            authenticatedPlayers.add(player.getUUID());
            failedAttempts.remove(player.getUUID());
            loginAttempts.remove(player.getUUID());
        } else {
            authenticatedPlayers.remove(player.getUUID());
        }
    }
    
    public void removePlayer(ServerPlayer player) {
        authenticatedPlayers.remove(player.getUUID());
        loginAttempts.remove(player.getUUID());
        failedAttempts.remove(player.getUUID());
        connectionTimes.remove(player.getUUID());
        preLoginCache.remove(player.getGameProfile().getName().toLowerCase()); // Limpiar también la caché al desconectar
    }

    /**
     * Se llama cuando el jugador se desconecta.
     * Guarda la IP y la hora de expiración en RAM.
     */
    public void pauseSession(ServerPlayer player) {
        // 1. Quitamos del set de autenticados activos
        setAuthenticated(player, false); 
        
        // 2. Calculamos expiración
        long durationSeconds = com.marcp.directauth.DirectAuth.getConfig().sessionGracePeriod;
        if (durationSeconds <= 0) return; // Si está desactivado (0), no guardamos nada

        long expiryTime = System.currentTimeMillis() + (durationSeconds * 1000);
        String ip = player.getIpAddress(); // NeoForge suele dar la IP sin puerto aquí

        // 3. Guardamos en el "Limbo"
        graceSessions.put(player.getUUID(), new GraceSession(ip, expiryTime));
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

        // Limpieza: Ya la hemos recuperado o vamos a descartarla, así que la borramos del mapa
        graceSessions.remove(uuid);

        // 1. Chequeo de Tiempo
        if (System.currentTimeMillis() > session.expirationTime) {
            return false; // Caducó
        }

        // 2. Chequeo de IP (CRÍTICO DE SEGURIDAD)
        String currentIp = player.getIpAddress();
        if (!session.ipAddress.equals(currentIp)) {
            // Log de advertencia opcional para admins
            com.marcp.directauth.DirectAuth.LOGGER.warn("Intento de sesión inválida (IP distinta) para {}", player.getName().getString());
            return false; 
        }

        // 3. Restaurar
        setAuthenticated(player, true);
        return true;
    }

    public void invalidateSession(ServerPlayer player) {
        graceSessions.remove(player.getUUID());
    }

    // Clase interna simple para guardar los datos
    private record GraceSession(String ipAddress, long expirationTime) {}

    public void recordJoin(ServerPlayer player) {
        connectionTimes.put(player.getUUID(), System.currentTimeMillis());
    }

    public boolean hasTimedOut(ServerPlayer player) {
        if (isAuthenticated(player)) return false; // Si ya está dentro, no hay timeout

        Long joinTime = connectionTimes.get(player.getUUID());
        if (joinTime == null) return false; // Por seguridad

        long elapsedSeconds = (System.currentTimeMillis() - joinTime) / 1000;
        return elapsedSeconds >= com.marcp.directauth.DirectAuth.getConfig().loginTimeout;
    }
    
    public boolean canAttemptLogin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Long lastAttempt = loginAttempts.get(uuid);
        
        if (lastAttempt != null) {
            long elapsed = System.currentTimeMillis() - lastAttempt;
            return elapsed >= COOLDOWN_MS;
        }
        return true;
    }
    
    public void recordLoginAttempt(ServerPlayer player, boolean success) {
        UUID uuid = player.getUUID();
        loginAttempts.put(uuid, System.currentTimeMillis());
        
        if (!success) {
            int attempts = failedAttempts.getOrDefault(uuid, 0) + 1;
            failedAttempts.put(uuid, attempts);
        }
    }
    
    public int getFailedAttempts(ServerPlayer player) {
        return failedAttempts.getOrDefault(player.getUUID(), 0);
    }
    
    public boolean hasExceededMaxAttempts(ServerPlayer player) {
        return getFailedAttempts(player) >= MAX_ATTEMPTS;
    }

    // MÉTODOS PARA LA PRE-CARGA
    public void addPreLoadedData(String username, UserData data) {
        // Guardamos el dato (incluso si es null, para saber que ya buscamos y no existe)
        if (username != null) {
            preLoginCache.put(username.toLowerCase(), data != null ? data : new UserData("NULL_MARKER", ""));
        }
    }

    public UserData getAndRemovePreLoadedData(String username) {
        if (username == null) return null;
        UserData data = preLoginCache.remove(username.toLowerCase());
        
        // Si es el marcador de "no existe", devolvemos null real
        if (data != null && "null_marker".equals(data.getUsername())) {
            return null;
        }
        return data;
    }

    public boolean isPreLoaded(String username) {
        if (username == null) return false;
        return preLoginCache.containsKey(username.toLowerCase());
    }
    
    // --- Hashing con PBKDF2 (Nativo Java) ---
    
    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        
        // Formato: salt:hash (Base64)
        return Base64.getEncoder().encodeToString(salt) + ":" + 
               Base64.getEncoder().encodeToString(hash);
    }
    
    public static boolean checkPassword(String password, String storedHash) {
        String[] parts = storedHash.split(":");
        if (parts.length != 2) return false;
        
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] originalHash = Base64.getDecoder().decode(parts[1]);
        
        byte[] newHash = pbkdf2(password.toCharArray(), salt);
        
        return Arrays.equals(originalHash, newHash);
    }
    
    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
