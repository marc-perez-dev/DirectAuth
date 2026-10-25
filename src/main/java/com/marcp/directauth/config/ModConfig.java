package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

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
    // Ids por defecto de las listas de otras mods, con la versión de estos valores por defecto en la
    // que se añadió cada uno. Una config escrita con una versión anterior recibe al cargarse los que
    // le falten (los usuarios no editan la config a mano); los que el admin quitó después no vuelven.
    private static final int OTHER_MODS_DEFAULTS = 1;
    private static final Map<String, Integer> DEFAULT_SCREEN_PAYLOADS = new LinkedHashMap<>();
    private static final Map<String, Integer> DEFAULT_HELD_EXCEPTIONS = new LinkedHashMap<>();
    static {
        // Forge 1.20.1: "canal#índice" en los SimpleChannel (ver JailTraffic.payloadType)
        DEFAULT_SCREEN_PAYLOADS.put("origins:network#1", 1);                              // Origins: elección de origen
        DEFAULT_SCREEN_PAYLOADS.put("mca:main#36", 1);                                    // MCA Reborn: Destiny
        DEFAULT_SCREEN_PAYLOADS.put("pixelmon:main#42", 1);                               // Pixelmon: elección de inicial
        DEFAULT_SCREEN_PAYLOADS.put("starterkit:to_client_receive_kit_data_packet", 1);   // Starter Kit: elección de kit (un canal por paquete)
        DEFAULT_HELD_EXCEPTIONS.put("starterkit:to_server_announce_mod_is_installed_packet", 1); // Starter Kit: "tengo la mod" (5 s de plazo)
        DEFAULT_HELD_EXCEPTIONS.put("tacz:network#30", 1);                                // TACZ: hora del cliente (sin ella el primer disparo falla)
        DEFAULT_HELD_EXCEPTIONS.put("architectury:network#architectury:sync_ids", 1);     // Architectury: qué paquetes acepta el cliente
    }

    // Paquetes de otras mods que abren una pantalla al entrar. A un jugador sin autenticar se le
    // entregan al autenticarse (solo el último de cada tipo), para que no le tapen el chat antes.
    // Cada entrada es un tipo ("canal#índice" en un SimpleChannel) o un canal entero.
    public List<String> deferredScreenPayloads = new ArrayList<>(DEFAULT_SCREEN_PAYLOADS.keySet());
    // Paquetes de otras mods que un jugador sin autenticar sí puede enviar. El resto se retiene
    // hasta que se autentica (y se descarta si se desconecta antes). Solo para respuestas que una
    // mod espera con plazo y que no hacen nada en nombre de la cuenta.
    public List<String> heldPayloadExceptions = new ArrayList<>(DEFAULT_HELD_EXCEPTIONS.keySet());
    // Escribe en el log los paquetes de otras mods que recibe un jugador sin autenticar, para
    // encontrar el tipo de una pantalla que añadir a deferredScreenPayloads.
    public boolean logJailPayloads = false;
    // Hasta qué versión de los valores por defecto de las dos listas anteriores se han aplicado
    public int otherModsDefaultsVersion = OTHER_MODS_DEFAULTS;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Runtime reference to the loaded language config
    private transient LangConfig langConfig;

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
            // Ids por defecto añadidos en versiones posteriores a la que escribió el fichero
            if (config.otherModsDefaultsVersion < OTHER_MODS_DEFAULTS) {
                addNewDefaults(config.deferredScreenPayloads, DEFAULT_SCREEN_PAYLOADS, config.otherModsDefaultsVersion);
                addNewDefaults(config.heldPayloadExceptions, DEFAULT_HELD_EXCEPTIONS, config.otherModsDefaultsVersion);
                config.otherModsDefaultsVersion = OTHER_MODS_DEFAULTS;
            }

            config.save(configPath); // Guardar para actualizar campos nuevos (y quitar los que ya no existen)
        } else {
            config = new ModConfig();
            config.save(configPath);
        }

        config.loadLanguages(configPath.getParent());
        return config;
    }

    private static void addNewDefaults(List<String> list, Map<String, Integer> defaults, int appliedVersion) {
        defaults.forEach((id, version) -> {
            if (version > appliedVersion && !list.contains(id)) list.add(id);
        });
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
