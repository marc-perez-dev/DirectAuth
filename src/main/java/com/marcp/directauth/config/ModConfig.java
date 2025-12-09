package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;

public class ModConfig {
    // --- General Messages ---
    public String msgWelcome = "§eWelcome! Use §a/register <password>§e to create your account.";
    public String msgLoginRequest = "§eUse §a/login <password>§e to authenticate.";
    public String msgAuthReminder = "§cYou must authenticate: /register <password> or /login <password>";
    public String errNotPlayer = "Only players can use this command.";
    
    // --- Registration Messages ---
    public String errAlreadyRegistered = "§cYou already have an account. Use §e/login <password>";
    public String errPasswordTooShort = "§cPassword must be at least 4 characters long.";
    public String errPasswordTooLong = "§cPassword cannot be longer than 32 characters.";
    public String msgRegistered = "§a✓ Account registered successfully.";
    public String msgPremiumEnableHint = "§7Do you have a paid Minecraft account? Use §b/online§7 to enable auto-login.";
    
    // --- Login Messages ---
    public String errNotRegistered = "§cYou do not have an account. Use §e/register <password>";
    public String errAlreadyAuthenticated = "§eYou are already authenticated.";
    public String errCooldown = "§cPlease wait a few seconds before trying again.";
    public String errMaxAttempts = "§cToo many failed attempts.";
    public String msgAuthenticated = "§a✓ Authenticated successfully.";
    public String errWrongPassword = "§cIncorrect password (%d/%d attempts).";
    public String msgTimeout = "§cLogin timed out.\n§7Please authenticate faster next time.";
    
    // --- Online Mode (formerly Premium) Messages ---
    public String msgPremiumHint = "§7Own a legitimate account? Use §b/online§7 after logging in.";
    public String msgAutoLogin = "§a✓ Automatically authenticated (Online Mode).";
    public String msgPremiumError = "§cAuthentication Error\n§7This account is registered in Online Mode,\n§7but your UUID does not match.\n§7If you own this account, contact an administrator.";
    public String errNotAuthenticated = "§cYou must authenticate first.";
    public String errUserNotFound = "§cError: Account not found.";
    public String msgAlreadyPremium = "§eYour account is already set to Online Mode.";
    public String msgVerifying = "§eVerifying account with Mojang...";
    public String errMojangNotFound = "§cNo paid Minecraft account found with this username.";
    public String msgMojangHint = "§7Ensure you are using a legitimate Minecraft account.";
    public String errUUIDMismatch = "§cYour UUID does not match the Mojang account.";
    public String msgSessionHint = "§7You are using an offline session.";
    public String msgPremiumSuccess = "§a✓ Account verified as Online Mode.";
    public String msgAutoLoginHint = "§7Auto-login is now enabled for this account.";

    // --- Admin Messages ---
    public String msgPremiumWarning = "§cWARNING! §7You are about to enable Online Mode.\n§7If you do not own this account, §cyou will lose access.\n§7Type §b/online <your_password> §7to confirm.";
    public String msgAdminPremiumUpdated = "§aOnline Mode status updated for %s: %s";
    public String errAdminUserNotFound = "§cUser %s does not exist in the database.";
    public String errAdminUsage = "§cUsage: /directauth online <user> <true|false>";

    // --- Restriction Messages ---
    public String msgNoDrop = "§cYou cannot drop items before authenticating.";
    public String msgUseCommands = "§cPlease use commands to authenticate first.";

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

    public static ModConfig load(Path configPath) {
        try {
            if (Files.exists(configPath)) {
                Reader reader = Files.newBufferedReader(configPath);
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                reader.close();
                config.save(configPath);
                return config;
            } else {
                ModConfig config = new ModConfig();
                config.save(configPath);
                return config;
            }
        } catch (IOException e) {
            System.err.println("Error loading config: " + e.getMessage());
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
            System.err.println("Error saving config: " + e.getMessage());
        }
    }
}