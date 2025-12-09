package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;

public class ModConfig {
    // --- Mensajes Generales ---
    public String msgWelcome = "§eBienvenido! Usa §a/register <contraseña>§e para crear tu cuenta";
    public String msgLoginRequest = "§eUsa §a/login <contraseña>§e para autenticarte";
    public String msgAuthReminder = "§cDebes autenticarte: /register <contraseña> o /login <contraseña>";
    public String errNotPlayer = "Solo jugadores pueden usar este comando";
    
    // --- Mensajes de Registro ---
    public String errAlreadyRegistered = "§cYa tienes una cuenta. Usa §e/login <contraseña>";
    public String errPasswordTooShort = "§cLa contraseña debe tener al menos 4 caracteres";
    public String errPasswordTooLong = "§cLa contraseña no puede tener más de 32 caracteres";
    public String msgRegistered = "§a✓ Cuenta registrada exitosamente";
    public String msgPremiumEnableHint = "§7¿Tienes Minecraft original? Usa §b/premium§7 para habilitar auto-login";
    
    // --- Mensajes de Login ---
    public String errNotRegistered = "§cNo tienes una cuenta. Usa §e/register <contraseña>";
    public String errAlreadyAuthenticated = "§eYa estás autenticado";
    public String errCooldown = "§cEspera unos segundos antes de intentar de nuevo";
    public String errMaxAttempts = "§cDemasiados intentos fallidos";
    public String msgAuthenticated = "§a✓ Autenticado exitosamente";
    public String errWrongPassword = "§cContraseña incorrecta (%d/%d intentos)";
    
    // --- Mensajes de Premium ---
    public String msgPremiumHint = "§7¿Tienes cuenta premium? Usa §b/premium§7 después de autenticarte";
    public String msgAutoLogin = "§a✓ Autenticado automáticamente (cuenta premium)";
    public String msgPremiumError = "§cError de autenticación\n§7Esta cuenta está registrada como premium\n§7pero tu UUID no coincide.\n§7Si eres el propietario, contacta con un administrador.";
    public String errNotAuthenticated = "§cDebes autenticarte primero";
    public String errUserNotFound = "§cError: no se encontró tu cuenta";
    public String msgAlreadyPremium = "§eTu cuenta ya está configurada como premium";
    public String msgVerifying = "§eVerificando tu cuenta con Mojang...";
    public String errMojangNotFound = "§cNo se encontró una cuenta premium con tu nombre";
    public String msgMojangHint = "§7Asegúrate de tener Minecraft original";
    public String errUUIDMismatch = "§cTu UUID no coincide con la cuenta premium";
    public String msgSessionHint = "§7Estás usando una sesión no premium";
    public String msgPremiumSuccess = "§a✓ Cuenta verificada como premium";
    public String msgAutoLoginHint = "§7A partir de ahora tendrás auto-login";

    // --- Mensajes de Premium (Warning/Admin) ---
    public String msgPremiumWarning = "§c¡ADVERTENCIA! §7Estás a punto de activar el modo premium.\n§7Si no tienes Minecraft original, §cperderás el acceso a tu cuenta.\n§7Escribe §b/premium <tu_contraseña> §7para confirmar.";
    public String msgAdminPremiumUpdated = "§aEstado premium actualizado para %s: %s";
    public String errAdminUserNotFound = "§cEl usuario %s no existe en la base de datos.";
    public String errAdminUsage = "§cUso: /directauth premium <usuario> <true|false>";

    // --- Mensajes de Restricción ---
    public String msgNoDrop = "§cNo puedes soltar objetos antes de autenticarte";
    public String msgUseCommands = "§cUsa comandos para autenticarte";

    // Configuración de seguridad
    public int minPasswordLength = 4;
    public int maxPasswordLength = 32;
    public int maxLoginAttempts = 5;
    public long loginCooldownMs = 3000;
    
    // Configuración de restricciones
    public boolean freezeUnauthenticated = true;
    public boolean blockChat = true;
    public boolean blockInteractions = true;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ModConfig load(Path configPath) {
        try {
            if (Files.exists(configPath)) {
                Reader reader = Files.newBufferedReader(configPath);
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                reader.close();
                // Ensure config saves new fields if they are missing (optional but good)
                config.save(configPath);
                return config;
            } else {
                ModConfig config = new ModConfig();
                config.save(configPath);
                return config;
            }
        } catch (IOException e) {
            System.err.println("Error cargando configuración: " + e.getMessage());
            return new ModConfig();
        }
    }

    public void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            Writer writer = Files.newBufferedWriter(configPath);
            GSON.toJson(this, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Error guardando configuración: " + e.getMessage());
        }
    }
}
