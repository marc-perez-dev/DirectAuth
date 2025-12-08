package com.marcp.directauth.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path databasePath;
    private Map<String, UserData> users; // Clave: username en minúsculas
    
    public DatabaseManager(Path worldPath) {
        // Ruta: world/serverconfig/DirectAuth_users.json
        this.databasePath = worldPath.resolve("serverconfig").resolve("DirectAuth_users.json");
        this.users = new HashMap<>();
        loadDatabase();
    }
    
    private void loadDatabase() {
        try {
            // Crear directorio si no existe
            Files.createDirectories(databasePath.getParent());
            
            if (Files.exists(databasePath)) {
                Reader reader = Files.newBufferedReader(databasePath);
                users = GSON.fromJson(reader, new TypeToken<Map<String, UserData>>(){}.getType());
                reader.close();
                
                if (users == null) {
                    users = new HashMap<>();
                }
            }
        } catch (IOException e) {
            System.err.println("Error cargando base de datos: " + e.getMessage());
            users = new HashMap<>();
        }
    }
    
    public synchronized void saveDatabase() {
        try {
            Writer writer = Files.newBufferedWriter(databasePath);
            GSON.toJson(users, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Error guardando base de datos: " + e.getMessage());
        }
    }
    
    public UserData getUser(String username) {
        return users.get(username.toLowerCase());
    }
    
    public boolean userExists(String username) {
        return users.containsKey(username.toLowerCase());
    }
    
    public void createUser(String username, String passwordHash) {
        users.put(username.toLowerCase(), new UserData(username, passwordHash));
        saveDatabase();
    }
    
    public void updateUser(String username, UserData data) {
        users.put(username.toLowerCase(), data);
        saveDatabase();
    }
}
