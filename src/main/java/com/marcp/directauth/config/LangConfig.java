package com.marcp.directauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.*;
import java.util.Map;

public class LangConfig {
    // --- General Messages ---
    public String msgWelcome = "§eWelcome! Use §a/register <password>§e to create your account.";
    public String msgLoginRequest = "§eUse §a/login <password>§e to authenticate.";
    public String msgAuthReminder = "§cYou must authenticate: /register <password> or /login <password>";
    public String errNotPlayer = "Only players can use this command.";
    public String errInternal = "§cAn internal error occurred. Please try again later.";
    public String errOperationPending = "§cPlease wait, your previous request is still being processed.";
    
    // --- Registration Messages ---
    public String errAlreadyRegistered = "§cYou already have an account. Use §e/login <password>";
    public String errPasswordTooShort = "§cPassword must be at least %d characters long.";
    public String errPasswordTooLong = "§cPassword cannot be longer than %d characters.";
    public String msgRegistered = "§a✓ Account registered successfully.";
    public String msgPremiumEnableHint = "§7Do you have a paid Minecraft account? Use §b/online§7 to enable auto-login.";
    public String errRegistrationCooldown = "§cPlease wait a moment before registering.";
    public String errIpLimitReached = "§cRegistration limit reached for this IP address.";
    
    // --- Login Messages ---
    public String errNotRegistered = "§cYou do not have an account. Use §e/register <password>";
    public String errAlreadyAuthenticated = "§eYou are already authenticated.";
    public String errCooldown = "§cPlease wait a few seconds before trying again.";
    public String errMaxAttempts = "§cToo many failed attempts. Please try again later.";
    public String msgAuthenticated = "§a✓ Authenticated successfully.";
    public String errWrongPassword = "§cIncorrect password (%d/%d attempts).";
    // Used where there is no attempt tracking (e.g. /unregister, /online), so it omits the counter.
    public String errWrongPasswordSimple = "§cIncorrect password.";
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
    public String errMojangUnavailable = "§cCould not reach Mojang's servers. Please try again later.";
    public String msgMojangHint = "§7Ensure you are using a legitimate Minecraft account.";
    public String errUUIDMismatch = "§cYour UUID does not match the Mojang account.";
    public String msgSessionHint = "§7You are using an offline session.";
    public String msgPremiumEnabled = "§a✓ Online Mode enabled. From your next join you will be logged in automatically with your Minecraft account.";
    public String msgAutoLoginHint = "§7Auto-login is now enabled for this account.";
    public String msgOnlineModeKeepsData = "§eYour inventory, progress and mod data stay as they are: Online Mode only changes how you log in (automatically, with your Minecraft account, instead of with a password).";

    // --- Admin Messages ---
    public String msgPremiumWarning = "§cWARNING! §7You are about to enable Online Mode.\n§7If you do not own this account, §cyou will lose access.\n§7Type §b/online <your_password> §7to confirm.";
    public String msgAdminPremiumUpdated = "§aOnline Mode status updated for %s: %s";
    public String errAdminUserNotFound = "§cUser %s does not exist in the database.";
    public String errAdminUsage = "§cUsage: /directauth online <user> <true|false>";
    public String errAdminUsageReset = "§cUsage: /directauth resetpass <user> <newPassword>";
    public String errAdminUsageUnregister = "§cUsage: /directauth unregister <user>";
    public String msgConfigReloaded = "§a✓ DirectAuth configuration reloaded.";
    public String errConfigReloadFailed = "§cDirectAuth-config.json could not be read (see the server log). The previous settings are still active.";
    public String errAdminEnableNeedsPlayer = "§c%s must be online and logged in to enable their Online Mode: their account keeps the UUID they play with, and it depends on the exact case of their name.";
    public String msgAdminPasswordResetKick = "§eAn administrator reset your password.\n§ePlease rejoin and log in with the new one.";
    public String msgLangReset = "§a✓ DirectAuth built-in (English/Spanish) language files reset to defaults.";

    // --- Restriction Messages ---
    public String msgNoDrop = "§cYou cannot drop items before authenticating.";
    public String msgUseCommands = "§cPlease use commands to authenticate first.";
    
    // --- Account Management & Confirmation ---
    public String msgConfirmRequest = "§e⚠️ Confirmation Required!\n§7You are about to perform a sensitive action.\n§7Type §6/directauth confirm§7 to proceed.";
    public String msgPasswordChanged = "§a✓ Password updated successfully.";
    public String msgPasswordChangedElsewhere = "§eThe password of this account was changed.\n§ePlease rejoin and log in again.";
    public String msgAccountDeleted = "§cYour account has been deleted.";
    public String errOldPasswordWrong = "§cThe old password is incorrect.";
    public String errNoPendingAction = "§cYou have no pending action to confirm.";
    public String msgActionExpired = "§cThe confirmation request has expired.";
    
    // --- Session Messages ---
    public String msgSessionRestored = "§aWelcome back. Session restored automatically.";
    public String msgLogoutSuccess = "§cLogged out successfully.";

    // --- Admin Management ---
    public String msgAdminResetSuccess = "§aPassword reset for user %s.";
    public String msgAdminUnregisterSuccess = "§aUser %s has been removed from the database.";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Formats a (possibly admin-edited) message template, falling back to the raw template
     * if it contains an invalid format specifier (e.g. a stray '%'). This prevents a typo
     * in a language file from throwing an exception in the middle of a command.
     */
    public static String format(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (java.util.IllegalFormatException e) {
            com.marcp.directauth.DirectAuth.LOGGER.warn(
                "DirectAuth: malformed format string in language file: {}", template);
            return template;
        }
    }

    public static LangConfig load(Path langPath, String language) {
        if (Files.exists(langPath)) {
            try (Reader reader = Files.newBufferedReader(langPath)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                JsonObject fileJson = (parsed != null && parsed.isJsonObject())
                        ? parsed.getAsJsonObject() : new JsonObject();

                // Start from the correct defaults for this language, then overlay the on-disk
                // values. This guarantees keys added in a mod update appear in the right
                // language instead of falling back to the English field defaults.
                LangConfig base = new LangConfig();
                base.setDefaults(language);
                JsonObject merged = GSON.toJsonTree(base).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : fileJson.entrySet()) {
                    merged.add(entry.getKey(), entry.getValue());
                }

                LangConfig config = GSON.fromJson(merged, LangConfig.class);
                config.save(langPath); // persist any newly added keys
                return config;
            } catch (Exception e) {
                System.err.println("DirectAuth: Error loading lang config: " + e.getMessage());
                LangConfig config = new LangConfig();
                config.setDefaults(language);
                return config;
            }
        } else {
            LangConfig config = new LangConfig();
            config.setDefaults(language);
            config.save(langPath);
            return config;
        }
    }

    public void setDefaults(String language) {
        if ("es".equalsIgnoreCase(language)) {
            // --- General Messages ---
            msgWelcome = "§e¡Bienvenido! Usa §a/register <contraseña>§e para crear tu cuenta.";
            msgLoginRequest = "§eUsa §a/login <contraseña>§e para autenticarte.";
            msgAuthReminder = "§cDebes autenticarte: /register <contraseña> o /login <contraseña>";
            errNotPlayer = "Solo los jugadores pueden usar este comando.";
            errInternal = "§cSe produjo un error interno. Inténtalo de nuevo más tarde.";
            errOperationPending = "§cEspera, tu petición anterior todavía se está procesando.";

            // --- Registration Messages ---
            errAlreadyRegistered = "§cYa tienes una cuenta. Usa §e/login <contraseña>";
            errPasswordTooShort = "§cLa contraseña debe tener al menos %d caracteres.";
            errPasswordTooLong = "§cLa contraseña no puede tener más de %d caracteres.";
            msgRegistered = "§a✓ Cuenta registrada exitosamente.";
            msgPremiumEnableHint = "§7¿Tienes una cuenta de Minecraft premium? Usa §b/online§7 para activar el auto-login.";
            errRegistrationCooldown = "§cPor favor, espera un momento antes de registrarte.";
            errIpLimitReached = "§cSe ha alcanzado el límite de registros para esta dirección IP.";

            // --- Login Messages ---
            errNotRegistered = "§cNo tienes una cuenta. Usa §e/register <contraseña>";
            errAlreadyAuthenticated = "§eYa estás autenticado.";
            errCooldown = "§cPor favor espera unos segundos antes de intentar de nuevo.";
            errMaxAttempts = "§cDemasiados intentos fallidos. Inténtalo de nuevo más tarde.";
            msgAuthenticated = "§a✓ Autenticado exitosamente.";
            errWrongPassword = "§cContraseña incorrecta (%d/%d intentos).";
            errWrongPasswordSimple = "§cContraseña incorrecta.";
            msgTimeout = "§cTiempo de espera agotado.\n§7Por favor autentícate más rápido la próxima vez.";

            // --- Online Mode (formerly Premium) Messages ---
            msgPremiumHint = "§7¿Cuenta original? Usa §b/online§7 después de entrar.";
            msgAutoLogin = "§a✓ Autenticado automáticamente (Modo Online).";
            msgPremiumError = "§cError de Autenticación\n§7Esta cuenta está en Modo Online,\n§7pero tu UUID no coincide.\n§7Si eres el dueño, contacta a un administrador.";
            errNotAuthenticated = "§cDebes autenticarte primero.";
            errUserNotFound = "§cError: Cuenta no encontrada.";
            msgAlreadyPremium = "§eTu cuenta ya está en Modo Online.";
            msgVerifying = "§eVerificando cuenta con Mojang...";
            errMojangNotFound = "§cNo se encontró una cuenta de Minecraft pagada con este nombre.";
            errMojangUnavailable = "§cNo se pudo contactar con los servidores de Mojang. Inténtalo de nuevo más tarde.";
            msgMojangHint = "§7Asegúrate de estar usando una cuenta legítima de Minecraft.";
            errUUIDMismatch = "§cTu UUID no coincide con la cuenta de Mojang.";
            msgSessionHint = "§7Estás usando una sesión offline.";
            msgPremiumEnabled = "§a✓ Modo Online activado. A partir de la próxima vez que entres, se iniciará sesión automáticamente con tu cuenta de Minecraft.";
            msgAutoLoginHint = "§7El auto-login está activado para esta cuenta.";
            msgOnlineModeKeepsData = "§eTu inventario, tu progreso y los datos de las mods se quedan como están: el Modo Online solo cambia cómo entras (automáticamente, con tu cuenta de Minecraft, en vez de con contraseña).";

            // --- Admin Messages ---
            msgPremiumWarning = "§c¡ADVERTENCIA! §7Estás a punto de activar el Modo Online.\n§7Si no eres dueño de esta cuenta, §cperderás el acceso.\n§7Escribe §b/online <tu_contraseña> §7para confirmar.";
            msgAdminPremiumUpdated = "§aEstado de Modo Online actualizado para %s: %s";
            errAdminUserNotFound = "§cEl usuario %s no existe en la base de datos.";
            errAdminUsage = "§cUso: /directauth online <usuario> <true|false>";
            errAdminUsageReset = "§cUso: /directauth resetpass <usuario> <nueva_contraseña>";
            errAdminUsageUnregister = "§cUso: /directauth unregister <usuario>";
            msgConfigReloaded = "§a✓ Configuración de DirectAuth recargada.";
            errConfigReloadFailed = "§cNo se pudo leer DirectAuth-config.json (consulta el log del servidor). Se mantiene la configuración anterior.";
            errAdminEnableNeedsPlayer = "§c%s debe estar conectado y con la sesión iniciada para activar su Modo Online: la cuenta conserva la UUID con la que juega, y esta depende de las mayúsculas exactas de su nombre.";
            msgAdminPasswordResetKick = "§eUn administrador ha restablecido tu contraseña.\n§eVuelve a entrar e inicia sesión con la nueva.";
            msgLangReset = "§a✓ Archivos de idioma integrados de DirectAuth (Inglés/Español) restablecidos a los valores por defecto.";

            // --- Restriction Messages ---
            msgNoDrop = "§cNo puedes soltar objetos antes de autenticarte.";
            msgUseCommands = "§cPor favor usa los comandos para autenticarte primero.";
            // --- Nuevas Traducciones ---
            msgConfirmRequest = "§e⚠️ ¡Confirmación Requerida!\n§7Estás a punto de realizar una acción delicada.\n§7Escribe §6/directauth confirm§7 para proceder.";
            msgPasswordChanged = "§a✓ Contraseña actualizada correctamente.";
            msgPasswordChangedElsewhere = "§eSe ha cambiado la contraseña de esta cuenta.\n§eVuelve a entrar e inicia sesión de nuevo.";
            msgAccountDeleted = "§cTu cuenta ha sido eliminada.";
            errOldPasswordWrong = "§cLa contraseña antigua es incorrecta.";
            errNoPendingAction = "§cNo tienes ninguna acción pendiente de confirmar.";
            msgActionExpired = "§cLa solicitud de confirmación ha caducado.";
            
            msgAdminResetSuccess = "§aContraseña restablecida para el usuario %s.";
            msgAdminUnregisterSuccess = "§aEl usuario %s ha sido eliminado de la base de datos.";
            
            // --- Session Messages ---
            msgSessionRestored = "§aBienvenido de nuevo. Sesión restaurada automáticamente.";
            msgLogoutSuccess = "§cHas cerrado sesión correctamente.";
        }
    }

    public void save(Path langPath) {
        try {
            Files.createDirectories(langPath.getParent());
            try (Writer writer = Files.newBufferedWriter(langPath)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("Error saving lang config: " + e.getMessage());
        }
    }
}
