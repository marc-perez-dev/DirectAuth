package com.marcp.directauth.auth;

import net.minecraft.server.level.ServerPlayer;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LoginManager {
    // Jugadores actualmente autenticados (UUID offline -> true)
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    
    // Cooldown de intentos fallidos (UUID -> timestamp del último intento)
    private final Map<UUID, Long> loginAttempts = new ConcurrentHashMap<>();
    
    // Contador de intentos fallidos (UUID -> contador)
    private final Map<UUID, Integer> failedAttempts = new ConcurrentHashMap<>();
    
    private static final long COOLDOWN_MS = 3000; // 3 segundos entre intentos
    private static final int MAX_ATTEMPTS = 5; // Máximo 5 intentos antes de kick
    
    // Configuración de PBKDF2
    private static final int ITERATIONS = 10000;
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
