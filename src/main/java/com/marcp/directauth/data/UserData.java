package com.marcp.directauth.data;

import java.util.Locale;

public class UserData {
    private String username; // Nombre en minúsculas (clave)
    private String passwordHash; // Hash PBKDF2WithHmacSHA256 (ver LoginManager)
    private boolean isPremium; // Si es cuenta premium verificada
    private String onlineUUID; // UUID online si es premium (null si no)
    // UUID con la que juega la cuenta, sea cual sea su modo (ver MixinServerLoginPacketListenerImpl).
    // null = la que da el login: la offline de su nombre, o la de Mojang en las cuentas que
    // activaron el Modo Online antes de 1.3.1 (entonces sus datos se migraban a esa UUID).
    private String playerUUID;
    
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
    public String getPlayerUUID() { return playerUUID; }
    
    public void setPasswordHash(String hash) { this.passwordHash = hash; }
    public void setPremium(boolean premium) { this.isPremium = premium; }
    public void setOnlineUUID(String uuid) { this.onlineUUID = uuid; }
    public void setPlayerUUID(String uuid) { this.playerUUID = uuid; }
}
