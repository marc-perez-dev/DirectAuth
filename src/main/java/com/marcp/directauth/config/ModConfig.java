package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.marcp.directauth.data.MigrationMode;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModConfig {
    // --- General Settings ---
    public String language = "en";

    // --- Session Settings ---
    // Tiempo en segundos para mantener la sesión tras desconexión (Default: 600s = 10 min)
    public int sessionGracePeriod = 600;
    
    // Intervalo en minutos para limpiar sesiones caducadas de la memoria (Default: 10 min)
    public int sessionCleanupInterval = 10;

    // Security Settings
    public int minPasswordLength = 4;
    public int maxPasswordLength = 32;
    public int maxLoginAttempts = 5;
    public long loginCooldownMs = 3000;
    public int loginTimeout = 60; // Time in seconds before kick
    // Seconds an account stays locked after maxLoginAttempts failed logins (counted per account, across reconnects)
    public int loginLockoutSeconds = 300;
    
    // Anti-Bot Settings
    public int registrationDelay = 1; // 1 second wait before registering
    public int maxAccountsPerIP = 5;  // Max 5 accounts per IP

    // --- Other mods ---
    // Paquetes de otras mods que abren una pantalla al entrar. A un jugador sin autenticar se le
    // entregan al autenticarse (solo el último de cada id), para que no le tapen el chat antes.
    public List<String> deferredScreenPayloads = new ArrayList<>(List.of(
        "neoorigins:open_origin_screen",                   // Neo Origins: elección de origen
        "mca:open_destiny_gui_request",                    // MCA Reborn: Destiny
        "pixelmon:starterlistpacket",                      // Pixelmon: elección de inicial
        "dragonsurvival:open_dragon_altar",                // Dragon Survival: altar de dragón
        "starterkit:to_client_receive_kit_data_packet"     // Starter Kit: elección de kit
    ));
    // Paquetes de otras mods que un jugador sin autenticar sí puede enviar. El resto se retiene
    // hasta que se autentica (y se descarta si se desconecta antes). Solo para respuestas que una
    // mod espera con plazo y que no hacen nada en nombre de la cuenta.
    public List<String> heldPayloadExceptions = new ArrayList<>(List.of(
        "starterkit:to_server_announce_mod_is_installed_packet" // Starter Kit: "tengo la mod" (5 s de plazo)
    ));
    // Escribe en el log los paquetes de otras mods que recibe un jugador sin autenticar, para
    // encontrar el id de una pantalla que añadir a deferredScreenPayloads.
    public boolean logJailPayloads = false;

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

    /** Thrown when the config file exists but cannot be read or parsed. */
    public static class ConfigException extends RuntimeException {
        public ConfigException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Loads the config (and the language files) from disk.
     * If the file does not exist it is created with defaults. If it exists but is unreadable or
     * malformed, a {@link ConfigException} is thrown and the file is left untouched, so the
     * admin's edits are not overwritten with defaults.
     */
    public static ModConfig load(Path configPath) {
        ModConfig config;
        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                config = GSON.fromJson(reader, ModConfig.class);
            } catch (IOException | JsonParseException e) {
                throw new ConfigException("Could not read " + configPath + ": " + e.getMessage(), e);
            }
            if (config == null) {
                throw new ConfigException("Could not read " + configPath + ": the file is empty", null);
            }

            // Listas puestas a null a mano: valores por defecto
            ModConfig defaults = new ModConfig();
            if (config.deferredScreenPayloads == null) config.deferredScreenPayloads = defaults.deferredScreenPayloads;
            if (config.heldPayloadExceptions == null) config.heldPayloadExceptions = defaults.heldPayloadExceptions;

            // Asegurar que el mapa no sea nulo si viene de una config vieja
            if (config.migrationMap == null || config.migrationMap.isEmpty()) {
                config.migrationMap = new LinkedHashMap<>();
                config.setDefaults();
            }
            // Un modo desconocido (typo en el JSON) llega como null: se ignora esa entrada, pero NO
            // se reescribe el fichero, para que el admin pueda corregirla en vez de perderla
            java.util.List<String> invalidModes = new java.util.ArrayList<>();
            config.migrationMap.forEach((folder, mode) -> { if (mode == null) invalidModes.add(folder); });
            if (invalidModes.isEmpty()) {
                config.save(configPath); // Guardar para actualizar campos nuevos
            } else {
                invalidModes.forEach(config.migrationMap::remove);
                com.marcp.directauth.DirectAuth.LOGGER.warn(
                    "DirectAuth: Unknown migration mode for {} in {} (valid: RENAME, DIRECTORY, TEXT_REPLACE). Those folders will NOT be migrated until it is fixed; the file was not rewritten.",
                    invalidModes, configPath.getFileName());
            }
        } else {
            config = new ModConfig();
            config.save(configPath);
        }

        config.loadLanguages(configPath.getParent());
        return config;
    }

    /** Built-in defaults plus the language files, used when the config file cannot be read at startup. */
    public static ModConfig defaults(Path configDir) {
        ModConfig config = new ModConfig();
        config.loadLanguages(configDir);
        return config;
    }

    private void loadLanguages(Path dir) {
        LangConfig.load(dir.resolve("DirectAuth-lang-en.json"), "en");
        LangConfig.load(dir.resolve("DirectAuth-lang-es.json"), "es");
        String langFileName = "DirectAuth-lang-" + language + ".json";
        langConfig = LangConfig.load(dir.resolve(langFileName), language);
    }

    public void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(this, writer);
            }
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
