package com.marcp.directauth.data;

public class UserData {
    private String username; // Nombre en minúsculas (clave)
    private String passwordHash; // Hash BCrypt
    private boolean isPremium; // Si es cuenta premium verificada
    private String onlineUUID; // UUID online si es premium (null si no)
    
    // Constructor para nuevos usuarios
    public UserData(String username, String passwordHash) {
        this.username = username.toLowerCase();
        this.passwordHash = passwordHash;
        this.isPremium = false;
        this.onlineUUID = null;
    }
    
    // Getters y Setters
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPremium() { return isPremium; }
    public String getOnlineUUID() { return onlineUUID; }
    
    public void setPasswordHash(String hash) { this.passwordHash = hash; }
    public void setPremium(boolean premium) { this.isPremium = premium; }
    public void setOnlineUUID(String uuid) { this.onlineUUID = uuid; }
}
