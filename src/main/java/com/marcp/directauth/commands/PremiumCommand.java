package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
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
        dispatcher.register(Commands.literal("premium")
            .executes(PremiumCommand::execute)
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().errNotPlayer));
            return 0;
        }
        
        // Verificar autenticación
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errNotAuthenticated));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errUserNotFound));
            return 0;
        }
        
        // Verificar si ya es premium
        if (userData.isPremium()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgAlreadyPremium));
            return 0;
        }
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgVerifying));
        
        // Consultar API de forma asíncrona
        MojangAPI.getOnlineUUID(username).thenAccept(uuid -> {
            // Ejecutar en el hilo del servidor
            context.getSource().getServer().execute(() -> {
                if (uuid == null) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().errMojangNotFound));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgMojangHint));
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
                    
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgPremiumSuccess));
                    
                    // KICK OBLIGATORIO:
                    // Es necesario desconectar al jugador para que al volver a entrar
                    // el servidor cargue el perfil con la nueva UUID (que ahora tiene los datos copiados).
                    player.connection.disconnect(Component.literal(
                        "§a¡Cuenta verificada!\n§ePor favor, vuelve a entrar para aplicar los cambios."
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
            // 1. Guardar el estado actual del jugador a disco para asegurar que no se pierda nada reciente
            ((PlayerListAccessor) player.getServer().getPlayerList()).callSave(player);

            String currentUUID = player.getStringUUID();
            
            // Si por alguna razón las UUID son iguales (ej. servidor ya en online-mode), no hacemos nada
            if (currentUUID.equals(targetUUIDString)) return true;

            // Directorios de datos
            File worldDir = player.getServer().getWorldPath(LevelResource.ROOT).toFile();
            File playerdataDir = player.getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile();
            File statsDir = new File(worldDir, "stats");
            File advancementsDir = new File(worldDir, "advancements");

            // --- Migrar .dat (Inventario, Enderchest, Posición, XP) ---
            File sourceDat = new File(playerdataDir, currentUUID + ".dat");
            File targetDat = new File(playerdataDir, targetUUIDString + ".dat");
            
            // También existe .dat_old a veces
            File sourceDatOld = new File(playerdataDir, currentUUID + ".dat_old");
            File targetDatOld = new File(playerdataDir, targetUUIDString + ".dat_old");

            // SAFETY: Backup existing premium data if it exists to prevent accidental data loss
            if (targetDat.exists()) {
                File backupDat = new File(playerdataDir, targetUUIDString + ".dat.bak");
                Files.copy(targetDat.toPath(), backupDat.toPath(), StandardCopyOption.REPLACE_EXISTING);
                DirectAuth.LOGGER.info("Created backup of existing premium data: {}", backupDat.getName());
            }

            if (sourceDat.exists()) {
                // Usamos REPLACE_EXISTING por si el jugador premium ya había entrado alguna vez antes
                // Esto sobrescribirá los datos "viejos" de la cuenta premium con los datos actuales de la cuenta offline
                Files.copy(sourceDat.toPath(), targetDat.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (sourceDatOld.exists()) {
                    Files.copy(sourceDatOld.toPath(), targetDatOld.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // --- Migrar Estadísticas (Statistics) ---
            File sourceStats = new File(statsDir, currentUUID + ".json");
            File targetStats = new File(statsDir, targetUUIDString + ".json");
            
            if (sourceStats.exists()) {
                // Asegurar que la carpeta stats exista
                statsDir.mkdirs();
                Files.copy(sourceStats.toPath(), targetStats.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // --- Migrar Logros/Avances (Advancements) ---
            File sourceAdv = new File(advancementsDir, currentUUID + ".json");
            File targetAdv = new File(advancementsDir, targetUUIDString + ".json");

            if (sourceAdv.exists()) {
                // Asegurar que la carpeta advancements exista
                advancementsDir.mkdirs();
                Files.copy(sourceAdv.toPath(), targetAdv.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            
            DirectAuth.LOGGER.info("Migrated data for {} from {} to {}", player.getName().getString(), currentUUID, targetUUIDString);
            return true;

        } catch (IOException e) {
            DirectAuth.LOGGER.error("Failed to migrate player data", e);
            return false;
        }
    }
}