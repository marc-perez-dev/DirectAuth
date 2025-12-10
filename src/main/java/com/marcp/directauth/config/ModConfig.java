package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.marcp.directauth.data.MigrationMode;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModConfig {
    // --- General Settings ---
    public String language = "en";

    // Security Settings
    public int minPasswordLength = 4;
    public int maxPasswordLength = 32;
    public int maxLoginAttempts = 5;
    public long loginCooldownMs = 3000;
    public int loginTimeout = 60; // Time in seconds before kick
    
    // Restriction Settings
    public boolean freezeUnauthenticated = true;
    public boolean blockChat = true;
    public boolean blockInteractions = true;

    // --- Data Migration Settings ---
    // Mapa: Ruta de la carpeta -> Modo de migración
    public Map<String, MigrationMode> migrationMap = new LinkedHashMap<>();
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Runtime reference to the loaded language config
    private transient LangConfig langConfig;

    public ModConfig() {
        setDefaults();
    }

    private void setDefaults() {
        // Vanilla Data
        migrationMap.put("playerdata", MigrationMode.RENAME);
        migrationMap.put("stats", MigrationMode.RENAME);
        migrationMap.put("advancements", MigrationMode.RENAME);

        // Mod Support - Defaults seguros
        
        // Sistema de tumbas (Carpeta con UUID)
        migrationMap.put("deaths", MigrationMode.DIRECTORY);
        
        // FTB Quests (Archivo .snbt con UUID, contenido interno SIN guiones)
        // Nota: A veces está en 'ftbquests/player_data', pero cubrimos la raíz por si acaso
        migrationMap.put("ftbquests", MigrationMode.TEXT_REPLACE);
        
        // SkinRestorer (JSON simple, solo importa el nombre del archivo)
        migrationMap.put("skinrestorer", MigrationMode.RENAME);
    }

    public static ModConfig load(Path configPath) {
        ModConfig config;
        try {
            if (Files.exists(configPath)) {
                Reader reader = Files.newBufferedReader(configPath);
                config = GSON.fromJson(reader, ModConfig.class);
                reader.close();
                
                // Asegurar que el mapa no sea nulo si viene de una config vieja
                if (config.migrationMap == null || config.migrationMap.isEmpty()) {
                    config.migrationMap = new LinkedHashMap<>();
                    config.setDefaults();
                }
                config.save(configPath); // Guardar para actualizar campos nuevos
            } else {
                config = new ModConfig();
                config.save(configPath);
            }
        } catch (IOException e) {
            System.err.println("Error loading config: " + e.getMessage());
            config = new ModConfig();
        }
        
        // Load language (sin cambios)
        Path dir = configPath.getParent();
        LangConfig.load(dir.resolve("DirectAuth-lang-en.json"), "en");
        LangConfig.load(dir.resolve("DirectAuth-lang-es.json"), "es");
        String langFileName = "DirectAuth-lang-" + config.language + ".json";
        Path langPath = dir.resolve(langFileName);
        config.langConfig = LangConfig.load(langPath, config.language);
        
        return config;
    }

    public void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            Writer writer = Files.newBufferedWriter(configPath);
            GSON.toJson(this, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Error saving config: " + e.getMessage());
        }
    }
    
    public LangConfig getLang() {
        if (langConfig == null) {
            langConfig = new LangConfig();
        }
        return langConfig;
    }
}
