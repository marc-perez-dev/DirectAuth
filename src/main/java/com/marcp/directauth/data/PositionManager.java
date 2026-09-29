package com.marcp.directauth.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PositionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path databasePath;
    private Map<UUID, StoredLocation> positions;

    // La escritura del JSON (que crece con cada jugador) se hace en su propio hilo, con una
    // copia del mapa; varias peticiones seguidas se agrupan en una sola escritura.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DirectAuth-Positions");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Map<UUID, StoredLocation>> pendingSnapshot = new AtomicReference<>();
    
    public PositionManager(Path worldPath) {
        this.databasePath = worldPath.resolve("serverconfig").resolve("DirectAuth_positions.json");
        this.positions = new HashMap<>();
        loadDatabase();
    }
    
    private void loadDatabase() {
        try {
            Files.createDirectories(databasePath.getParent());
            if (Files.exists(databasePath)) {
                try (Reader reader = Files.newBufferedReader(databasePath)) {
                    positions = GSON.fromJson(reader, new TypeToken<Map<UUID, StoredLocation>>(){}.getType());
                }
                if (positions == null) positions = new HashMap<>();
            }
        } catch (IOException | JsonParseException e) {
            // Fichero corrupto (p. ej. truncado por un crash): lo apartamos en vez de tumbar el arranque
            positions = new HashMap<>();
            Path backup = databasePath.resolveSibling(databasePath.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(databasePath, backup);
                LOGGER.error("DirectAuth: Could not read saved positions ({}). The file was moved to {}", e.getMessage(), backup.getFileName());
            } catch (IOException moveError) {
                LOGGER.error("DirectAuth: Could not read saved positions ({})", e.getMessage());
            }
        }
    }
    
    /** Programa una escritura con el estado actual (llamar desde el hilo del servidor). */
    private void saveDatabase() {
        if (pendingSnapshot.getAndSet(new HashMap<>(positions)) == null) {
            try {
                ioExecutor.execute(() -> writeSnapshot(pendingSnapshot.getAndSet(null)));
            } catch (RejectedExecutionException e) {
                writeSnapshot(pendingSnapshot.getAndSet(null)); // Ya cerrado: escribir aquí mismo
            }
        }
    }

    /** Espera a que se escriban los cambios pendientes (al detener el servidor). */
    public void close() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("DirectAuth: Saving positions did not finish in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeSnapshot(Map<UUID, StoredLocation> snapshot) {
        if (snapshot == null) return;
        // Escritura atómica: primero a un temporal y luego se renombra encima del original,
        // para que un crash a mitad no deje el JSON truncado.
        Path tmp = databasePath.resolveSibling(databasePath.getFileName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(snapshot, writer);
            }
            try {
                Files.move(tmp, databasePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, databasePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("DirectAuth: Error saving positions: {}", e.getMessage());
        }
    }
    
    public void savePosition(ServerPlayer player) {
        // Solo guardamos si no tenemos ya una posición (para no sobrescribir la original con el spawn)
        if (!positions.containsKey(player.getUUID())) {
            positions.put(player.getUUID(), StoredLocation.fromPlayer(player));
            saveDatabase();
        }
    }
    
    public boolean restorePosition(ServerPlayer player) {
        StoredLocation loc = positions.remove(player.getUUID());
        if (loc != null) {
            loc.teleportPlayer(player);
            saveDatabase();
            return true;
        }
        return false;
    }
    
    public boolean hasPosition(UUID uuid) {
        return positions.containsKey(uuid);
    }
}
