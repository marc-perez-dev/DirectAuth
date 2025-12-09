package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;

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
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Runtime reference to the loaded language config (not saved in the main config file)
    private transient LangConfig langConfig;

    public static ModConfig load(Path configPath) {
        ModConfig config;
        try {
            if (Files.exists(configPath)) {
                Reader reader = Files.newBufferedReader(configPath);
                config = GSON.fromJson(reader, ModConfig.class);
                reader.close();
                config.save(configPath); // Save to update any new fields
            } else {
                config = new ModConfig();
                config.save(configPath);
            }
        } catch (IOException e) {
            System.err.println("Error loading config: " + e.getMessage());
            config = new ModConfig();
        }
        
        // Load language
        Path dir = configPath.getParent();
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
            langConfig = new LangConfig(); // Fallback
        }
        return langConfig;
    }
}