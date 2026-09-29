package com.marcp.directauth.data;

import com.marcp.directauth.DirectAuth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class MigrationManager {

    /** Un paso ya aplicado que sabe deshacerse. */
    @FunctionalInterface
    private interface Undo {
        void undo() throws IOException;
    }

    /**
     * Migración aplicada. Guarda cómo deshacer cada paso para poder revertirla entera si algo
     * falla después (por ejemplo, si no se puede guardar el cambio en la base de datos).
     */
    public static final class Migration {
        private final Deque<Undo> steps = new ArrayDeque<>();

        private void record(Undo undo) {
            steps.push(undo);
        }

        /** Deshace los pasos en orden inverso. Registra (sin lanzar) los que no se puedan deshacer. */
        public void rollback() {
            while (!steps.isEmpty()) {
                try {
                    steps.pop().undo();
                } catch (IOException e) {
                    DirectAuth.LOGGER.error("DirectAuth Migration: Could not undo a migration step", e);
                }
            }
        }
    }

    /**
     * Migra los datos de jugador de {@code oldUUID} a {@code targetUUID} según el migrationMap
     * de ModConfig. Si algún paso falla, deshace los anteriores y lanza la excepción, de modo
     * que los datos nunca quedan repartidos entre las dos UUID.
     */
    public static Migration migratePlayerData(MinecraftServer server, String oldUUID, String targetUUIDString) throws IOException {
        Migration migration = new Migration();
        if (oldUUID.equals(targetUUIDString)) return migration;

        try {
            // Preparamos las variantes sin guiones (necesario para FTB Quests interno)
            String oldUUIDNoDash = oldUUID.replace("-", "");
            String newUUIDNoDash = targetUUIDString.replace("-", "");

            File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();
            Map<String, MigrationMode> migrationMap = DirectAuth.getConfig().migrationMap;

            for (Map.Entry<String, MigrationMode> entry : migrationMap.entrySet()) {
                String folderName = entry.getKey();
                MigrationMode mode = entry.getValue();

                File folder = new File(worldDir, folderName);

                // Si la carpeta del mod no existe, la ignoramos silenciosamente
                if (!folder.exists() || !folder.isDirectory()) continue;

                // --- ESTRATEGIA 1: DIRECTORIOS (ej. deaths/UUID/) ---
                if (mode == MigrationMode.DIRECTORY) {
                    Path sourceDir = folder.toPath().resolve(oldUUID);
                    // Solo si existe la carpeta con la UUID vieja
                    if (Files.isDirectory(sourceDir)) {
                        Path targetDir = folder.toPath().resolve(targetUUIDString);

                        // Si ya existe destino (raro), backup
                        if (Files.exists(targetDir)) {
                            Path backup = folder.toPath().resolve(targetUUIDString + "_bak_" + System.currentTimeMillis());
                            move(migration, targetDir, backup);
                        }

                        move(migration, sourceDir, targetDir);
                        DirectAuth.LOGGER.info("DirectAuth Migration: Folder moved {} -> {}", sourceDir.getFileName(), targetDir.getFileName());
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
                        Path target = folder.toPath().resolve(newFileName);

                        // 1. Mover/Renombrar (con backup del destino si ya existía)
                        if (Files.exists(target)) {
                            move(migration, target, uniqueBackup(target));
                        }
                        move(migration, sourceFile.toPath(), target);
                        DirectAuth.LOGGER.info("DirectAuth Migration: File migrated {}", target.getFileName());

                        // 2. Si es TEXT_REPLACE, abrimos el archivo nuevo y sustituimos contenidos
                        if (mode == MigrationMode.TEXT_REPLACE) {
                            processTextReplacement(migration, target, oldUUID, targetUUIDString, oldUUIDNoDash, newUUIDNoDash);
                        }
                    }
                }
            }
            return migration;

        } catch (IOException | RuntimeException e) {
            DirectAuth.LOGGER.error("DirectAuth Migration: Failed migrating {} -> {}, rolling back", oldUUID, targetUUIDString, e);
            migration.rollback();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    private static Path uniqueBackup(Path target) {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (Files.exists(backup)) {
            backup = target.resolveSibling(target.getFileName() + ".bak_" + System.currentTimeMillis());
        }
        return backup;
    }

    private static void move(Migration migration, Path source, Path target) throws IOException {
        Files.move(source, target);
        migration.record(() -> Files.move(target, source, StandardCopyOption.REPLACE_EXISTING));
    }

    private static void processTextReplacement(Migration migration, Path file, String oldDash, String newDash, String oldNoDash, String newNoDash) throws IOException {
        byte[] originalBytes = Files.readAllBytes(file);
        // ISO-8859-1 asigna un carácter a cada byte, así que la ida y vuelta es exacta para
        // cualquier fichero (UTF-8 o no). Las UUID son ASCII y en UTF-8 nunca aparecen dentro
        // de un carácter multibyte, así que el reemplazo es seguro.
        String originalContent = new String(originalBytes, StandardCharsets.ISO_8859_1);

        // Reemplazo 1: UUID con guiones (Estándar)
        String content = originalContent.replace(oldDash, newDash);
        
        // Reemplazo 2: UUID sin guiones (Formato interno FTB Quests / Hex)
        content = content.replace(oldNoDash, newNoDash);

        if (!content.equals(originalContent)) {
            // Registrar el deshacer ANTES de escribir: si la escritura falla a medias, el
            // rollback restaura el contenido original.
            migration.record(() -> Files.write(file, originalBytes));
            Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
            DirectAuth.LOGGER.info("DirectAuth Migration: Content updated (internal IDs) in {}", file.getFileName());
        }
    }
}
