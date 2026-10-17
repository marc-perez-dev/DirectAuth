package com.marcp.directauth;

import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.commands.LoginCommand;
import com.marcp.directauth.commands.PremiumCommand;
import com.marcp.directauth.commands.RegisterCommand;
import com.marcp.directauth.commands.ChangePasswordCommand;
import com.marcp.directauth.commands.DirectAuthCommand;
import com.marcp.directauth.commands.UnregisterCommand;
import com.marcp.directauth.commands.LogoutCommand;
import com.marcp.directauth.config.ModConfig;
import com.marcp.directauth.data.DatabaseManager;
import com.marcp.directauth.data.PositionManager;
import com.marcp.directauth.events.ConnectionHandler;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.nio.file.Path;

@Mod(DirectAuth.MODID)
public class DirectAuth {
    public static final String MODID = "directauth";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static DatabaseManager database;
    private static PositionManager positionManager;
    private static LoginManager loginManager;
    private static ModConfig config;
    
    public DirectAuth() {
        loginManager = new LoginManager();
        
        // Registrar manejadores de eventos en el bus de juego (MinecraftForge.EVENT_BUS)
        // ConnectionHandler gestiona Login/Logout y ServerStarted
        MinecraftForge.EVENT_BUS.register(new ConnectionHandler());
        
        // PlayerRestrictionHandler gestiona restricciones de movimiento y acciones
        MinecraftForge.EVENT_BUS.register(new PlayerRestrictionHandler());
        
        // Registrar comandos cuando el evento se dispare
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }
    
    private void onRegisterCommands(RegisterCommandsEvent event) {
        RegisterCommand.register(event.getDispatcher());
        LoginCommand.register(event.getDispatcher());
        PremiumCommand.register(event.getDispatcher());
        
        // Nuevos Comandos
        ChangePasswordCommand.register(event.getDispatcher());
        UnregisterCommand.register(event.getDispatcher());
        DirectAuthCommand.register(event.getDispatcher()); // Reemplaza a PremiumAdminCommand
        LogoutCommand.register(event.getDispatcher());
    }
    
    public static void initDatabase(Path worldPath) {
        database = new DatabaseManager(worldPath);
        positionManager = new PositionManager(worldPath);
    }
    
    /**
     * Carga la configuración desde disco.
     * @return false si el fichero existe pero no se pudo leer; en ese caso se mantiene la
     *         configuración anterior (o los valores por defecto si es el primer arranque) y
     *         el fichero no se toca.
     */
    public static boolean initConfig(Path configPath) {
        try {
            config = ModConfig.load(configPath);
            return true;
        } catch (ModConfig.ConfigException e) {
            LOGGER.error("DirectAuth: {}. {}", e.getMessage(),
                config == null ? "Using built-in defaults until the file is fixed" : "Keeping the previous settings");
            if (config == null) {
                config = ModConfig.defaults(configPath.getParent());
            }
            return false;
        }
    }

    /** Sustituye el LoginManager por uno nuevo (sin estado del servidor anterior). */
    public static void resetLoginManager() {
        loginManager = new LoginManager();
    }

    public static void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
        if (positionManager != null) {
            positionManager.close();
        }
    }
    
    public static DatabaseManager getDatabase() {
        return database;
    }
    
    public static PositionManager getPositionManager() {
        return positionManager;
    }
    
    public static LoginManager getLoginManager() {
        return loginManager;
    }
    
    public static ModConfig getConfig() {
        return config;
    }
}
