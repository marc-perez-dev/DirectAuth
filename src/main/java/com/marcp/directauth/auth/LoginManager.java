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
        if (data != null && "NULL_MARKER".equals(data.getUsername())) {
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
