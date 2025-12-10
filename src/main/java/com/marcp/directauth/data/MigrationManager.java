package com.marcp.directauth.data;

import com.marcp.directauth.DirectAuth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class MigrationManager {

    /**
     * Migra los datos del jugador basándose en la configuración definida en ModConfig.
     */
    public static boolean migratePlayerData(ServerPlayer player, String targetUUIDString) {
        try {
            String oldUUID = player.getStringUUID(); // UUID Original (con guiones)
            if (oldUUID.equals(targetUUIDString)) return true;

            // Preparamos las variantes sin guiones (necesario para FTB Quests interno)
            String oldUUIDNoDash = oldUUID.replace("-", "");
            String newUUIDNoDash = targetUUIDString.replace("-", "");

            File worldDir = player.getServer().getWorldPath(LevelResource.ROOT).toFile();
            Map<String, MigrationMode> migrationMap = DirectAuth.getConfig().migrationMap;

            for (Map.Entry<String, MigrationMode> entry : migrationMap.entrySet()) {
                String folderName = entry.getKey();
                MigrationMode mode = entry.getValue();

                File folder = new File(worldDir, folderName);
                
                // Si la carpeta del mod no existe, la ignoramos silenciosamente
                if (!folder.exists() || !folder.isDirectory()) continue;

                // --- ESTRATEGIA 1: DIRECTORIOS (ej. deaths/UUID/) ---
                if (mode == MigrationMode.DIRECTORY) {
                    File sourceDir = new File(folder, oldUUID);
                    // Solo si existe la carpeta con la UUID vieja
                    if (sourceDir.exists() && sourceDir.isDirectory()) {
                        File targetDir = new File(folder, targetUUIDString);
                        
                        // Si ya existe destino (raro), backup
                        if (targetDir.exists()) {
                            File backup = new File(folder, targetUUIDString + "_bak_" + System.currentTimeMillis());
                            targetDir.renameTo(backup);
                        }
                        
                        boolean success = sourceDir.renameTo(targetDir);
                        if (success) {
                            DirectAuth.LOGGER.info("DirectAuth Migration: Carpeta movida {} -> {}", sourceDir.getName(), targetDir.getName());
                        } else {
                            DirectAuth.LOGGER.error("DirectAuth Migration: Fallo al mover carpeta {}", sourceDir.getPath());
                        }
                    }
                    continue; // Pasamos a la siguiente entrada de config
                }

                // --- ESTRATEGIA 2 & 3: ARCHIVOS (RENAME o TEXT_REPLACE) ---
                // Buscamos archivos que EMPIECEN por la UUID vieja (ej. UUID.dat, UUID.snbt, UUID.json)
                File[] filesToMigrate = folder.listFiles((dir, name) -> name.startsWith(oldUUID));

                if (filesToMigrate != null) {
                    for (File sourceFile : filesToMigrate) {
                        // Generar nuevo nombre conservando la extensión (ej. .snbt)
                        String newFileName = sourceFile.getName().replace(oldUUID, targetUUIDString);
                        File targetFile = new File(folder, newFileName);

                        // 1. Mover/Renombrar
                        moveOrMerge(sourceFile, targetFile);

                        // 2. Si es TEXT_REPLACE, abrimos el archivo nuevo y sustituimos contenidos
                        if (mode == MigrationMode.TEXT_REPLACE) {
                            processTextReplacement(targetFile, oldUUID, targetUUIDString, oldUUIDNoDash, newUUIDNoDash);
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

    private static void moveOrMerge(File source, File target) throws IOException {
        if (target.exists()) {
            // Backup simple si ya existía el archivo destino
            File backup = new File(target.getParent(), target.getName() + ".bak");
            Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        DirectAuth.LOGGER.info("DirectAuth Migration: Archivo migrado {}", target.getName());
    }

    private static void processTextReplacement(File file, String oldDash, String newDash, String oldNoDash, String newNoDash) {
        try {
            String content = Files.readString(file.toPath());
            String originalContent = content;

            // Reemplazo 1: UUID con guiones (Estándar)
            content = content.replace(oldDash, newDash);
            
            // Reemplazo 2: UUID sin guiones (Formato interno FTB Quests / Hex)
            content = content.replace(oldNoDash, newNoDash);

            if (!content.equals(originalContent)) {
                Files.writeString(file.toPath(), content);
                DirectAuth.LOGGER.info("DirectAuth Migration: Contenido actualizado (IDs internas) en {}", file.getName());
            }
        } catch (IOException e) {
            DirectAuth.LOGGER.error("DirectAuth Migration: Error leyendo/escribiendo archivo de texto {}", file.getName(), e);
        }
    }
}
