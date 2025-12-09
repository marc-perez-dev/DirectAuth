package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.UserData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource; // Importante para las rutas
import com.marcp.directauth.mixin.PlayerListAccessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class PremiumCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("online")
            .executes(context -> execute(context, null))
            .then(Commands.argument("password", StringArgumentType.string())
                .executes(context -> execute(context, StringArgumentType.getString(context, "password")))
            )
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context, String password) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        
        // Verificar autenticación
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
            return 0;
        }
        
        // Verificar si ya es premium
        if (userData.isPremium()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAlreadyPremium));
            return 0;
        }

        // Si no hay contraseña, mostramos advertencias
        if (password == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgOnlineModeWarning));
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumWarning));
            return 1;
        }

        // Verificar contraseña
        if (!LoginManager.checkPassword(password, userData.getPasswordHash())) {
             player.sendSystemMessage(Component.literal(String.format(
                DirectAuth.getConfig().getLang().errWrongPassword, 
                1, 
                DirectAuth.getConfig().maxLoginAttempts
            )));
             return 0;
        }
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgVerifying));
        
        // Consultar API de forma asíncrona
        MojangAPI.getOnlineUUID(username).thenAccept(uuid -> {
            // Ejecutar en el hilo del servidor
            context.getSource().getServer().execute(() -> {
                if (uuid == null) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errMojangNotFound));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgMojangHint));
                } else {
                    String formattedUUID = MojangAPI.formatUUID(uuid);
                    
                    // --- MIGRACIÓN DE DATOS ---
                    boolean migrationSuccess = migratePlayerData(player, formattedUUID);
                    
                    if (!migrationSuccess) {
                        DirectAuth.LOGGER.error("Error migrando datos para {}", username);
                        // Opcional: Avisar al usuario o detener el proceso si es crítico
                    }

                    // Actualizar a premium
                    userData.setPremium(true);
                    userData.setOnlineUUID(formattedUUID); // Guardamos la UUID real de Mojang
                    DirectAuth.getDatabase().updateUser(username, userData);
                    
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumSuccess));
                    
                    // KICK OBLIGATORIO:
                    // Es necesario desconectar al jugador para que al volver a entrar
                    // el servidor cargue el perfil con la nueva UUID (que ahora tiene los datos copiados).
                    player.connection.disconnect(Component.literal(
                        DirectAuth.getConfig().getLang().msgPremiumKick
                    ));
                }
            });
        });
        
        return 1;
    }

    /**
     * Mueve los datos del jugador de la UUID Offline a la UUID Online.
     */
    private static boolean migratePlayerData(ServerPlayer player, String targetUUIDString) {
        try {
            // 1. Guardar estado actual
            ((PlayerListAccessor) player.getServer().getPlayerList()).callSave(player);

            String currentUUID = player.getStringUUID();
            if (currentUUID.equals(targetUUIDString)) return true;

            File worldDir = player.getServer().getWorldPath(LevelResource.ROOT).toFile();
            
            // Iteramos sobre las carpetas definidas en la configuración
            for (String folderName : DirectAuth.getConfig().foldersToMigrate) {
                File folder = new File(worldDir, folderName);
                
                if (!folder.exists() || !folder.isDirectory()) continue;

                // Buscamos TODOS los archivos que empiecen por la UUID vieja
                // Esto cubre: UUID.dat, UUID.json, UUID.dat_old, UUID_1.dat, etc.
                File[] filesToMigrate = folder.listFiles((dir, name) -> name.startsWith(currentUUID));

                if (filesToMigrate != null) {
                    for (File sourceFile : filesToMigrate) {
                        // Generar nombre destino reemplazando la UUID vieja por la nueva
                        // Ejemplo: "viejaUUID.json" -> "nuevaUUID.json"
                        // Ejemplo: "viejaUUID_backup.dat" -> "nuevaUUID_backup.dat"
                        String newFileName = sourceFile.getName().replace(currentUUID, targetUUIDString);
                        File targetFile = new File(folder, newFileName);

                        // SAFETY: Si ya existe datos premium, hacer backup antes de sobrescribir
                        if (targetFile.exists()) {
                            File backupFile = new File(folder, newFileName + ".bak");
                            Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            DirectAuth.LOGGER.info("Backup created for existing premium data: {}/{}", folderName, backupFile.getName());
                        }

                        // Mover/Copiar los datos
                        try {
                            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            DirectAuth.LOGGER.info("Migrated: {}/{} -> {}", folderName, sourceFile.getName(), newFileName);
                        } catch (IOException e) {
                            DirectAuth.LOGGER.error("Failed to migrate file: {}/{}", folderName, sourceFile.getName(), e);
                        }
                    }
                }
            }
            
            return true;

        } catch (Exception e) {
            DirectAuth.LOGGER.error("CRITICAL ERROR during migration for {}", player.getName().getString(), e);
            return false;
        }
    }
}
