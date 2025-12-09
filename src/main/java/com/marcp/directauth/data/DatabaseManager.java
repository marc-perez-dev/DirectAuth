package com.marcp.directauth.data;

import java.nio.file.Path;
import java.sql.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.Map;

public class DatabaseManager {
    private final String connectionString;
    private Connection connection;

    public DatabaseManager(Path worldPath) {
        // Guardamos en world/serverconfig/directauth.db
        Path dbPath = worldPath.resolve("serverconfig").resolve("directauth.db");
        this.connectionString = "jdbc:sqlite:" + dbPath.toString();
        
        initDatabase();
        
        // MIGRACIÓN AUTOMÁTICA: Si existe el JSON viejo, lo importamos
        Path oldJsonPath = worldPath.resolve("serverconfig").resolve("DirectAuth_users.json");
        if (Files.exists(oldJsonPath)) {
            migrateFromJson(oldJsonPath);
        }
    }

    private void initDatabase() {
        try {
            // 1. Forzamos la carga del driver (Crucial en entornos moddeados)
            Class.forName("org.sqlite.JDBC");
            
            // 2. Establecemos conexión
            connection = DriverManager.getConnection(connectionString);
            
            try (Statement stmt = connection.createStatement()) {
                // Creamos la tabla si no existe
                stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY, " +
                        "passwordHash TEXT NOT NULL, " +
                        "isPremium INTEGER DEFAULT 0, " +
                        "onlineUUID TEXT" +
                        ");");
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("CRITICAL: No se encontró el driver de SQLite. Asegúrate de que la librería está incluida en el mod.", e);
        } catch (SQLException e) {
            throw new RuntimeException("CRITICAL: Error al conectar con la base de datos SQLite.", e);
        }
    }

    // --- MÉTODOS CRUD ---

    public UserData getUser(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                UserData data = new UserData(rs.getString("username"), rs.getString("passwordHash"));
                data.setPremium(rs.getInt("isPremium") == 1);
                data.setOnlineUUID(rs.getString("onlineUUID"));
                return data;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void createUser(String username, String passwordHash) {
        String sql = "INSERT INTO users(username, passwordHash, isPremium, onlineUUID) VALUES(?,?,0,NULL)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, username.toLowerCase());
            pstmt.setString(2, passwordHash);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void updateUser(String username, UserData data) {
        String sql = "UPDATE users SET passwordHash = ?, isPremium = ?, onlineUUID = ? WHERE username = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, data.getPasswordHash());
            pstmt.setInt(2, data.isPremium() ? 1 : 0);
            pstmt.setString(3, data.getOnlineUUID());
            pstmt.setString(4, username.toLowerCase());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // --- MIGRACIÓN (Solo se ejecuta una vez) ---
    private void migrateFromJson(Path jsonPath) {
        System.out.println("DirectAuth: Migrando base de datos JSON a SQLite...");
        try {
            Gson gson = new Gson();
            String jsonContent = Files.readString(jsonPath);
            Map<String, UserData> legacyUsers = gson.fromJson(jsonContent, new TypeToken<Map<String, UserData>>(){}.getType());
            
            if (legacyUsers != null) {
                // Usamos una transacción para que sea rápido
                connection.setAutoCommit(false);
                String sql = "INSERT OR IGNORE INTO users(username, passwordHash, isPremium, onlineUUID) VALUES(?,?,?,?)";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    for (UserData user : legacyUsers.values()) {
                        pstmt.setString(1, user.getUsername().toLowerCase());
                        pstmt.setString(2, user.getPasswordHash());
                        pstmt.setInt(3, user.isPremium() ? 1 : 0);
                        pstmt.setString(4, user.getOnlineUUID());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    e.printStackTrace();
                } finally {
                    connection.setAutoCommit(true);
                }
            }
            
            // Renombrar el JSON para no volver a importarlo
            Files.move(jsonPath, jsonPath.resolveSibling("DirectAuth_users.json.MIGRATED"));
            System.out.println("DirectAuth: Migración completada.");
            
        } catch (IOException | SQLException e) {
            System.err.println("Error migrando JSON: " + e.getMessage());
        }
    }
}