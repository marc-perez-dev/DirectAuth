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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
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
    
    public DirectAuth(IEventBus modEventBus) {
        loginManager = new LoginManager();
        
        // Registrar manejadores de eventos en el bus de juego (NeoForge.EVENT_BUS)
        // ConnectionHandler gestiona Login/Logout y ServerStarted
        NeoForge.EVENT_BUS.register(new ConnectionHandler());
        
        // PlayerRestrictionHandler gestiona restricciones de movimiento y acciones
        NeoForge.EVENT_BUS.register(new PlayerRestrictionHandler());
        
        // Registrar comandos cuando el evento se dispare
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
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
    
    public static void initConfig(Path configPath) {
        config = ModConfig.load(configPath);
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
