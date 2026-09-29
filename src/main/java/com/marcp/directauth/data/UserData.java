package com.marcp.directauth.data;

import java.util.Locale;

public class UserData {
    private String username; // Nombre en minúsculas (clave)
    private String passwordHash; // Hash PBKDF2WithHmacSHA256 (ver LoginManager)
    private boolean isPremium; // Si es cuenta premium verificada
    private String onlineUUID; // UUID online si es premium (null si no)
    private String offlineUUID; // UUID offline exacta desde la que se migró al activar premium (null si no se sabe)
    
    // Constructor para nuevos usuarios
    public UserData(String username, String passwordHash) {
        this.username = username.toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.isPremium = false;
        this.onlineUUID = null;
    }
    
    // Getters y Setters
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPremium() { return isPremium; }
    public String getOnlineUUID() { return onlineUUID; }
    public String getOfflineUUID() { return offlineUUID; }
    
    public void setPasswordHash(String hash) { this.passwordHash = hash; }
    public void setPremium(boolean premium) { this.isPremium = premium; }
    public void setOnlineUUID(String uuid) { this.onlineUUID = uuid; }
    public void setOfflineUUID(String uuid) { this.offlineUUID = uuid; }
}
