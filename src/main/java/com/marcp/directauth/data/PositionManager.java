package com.marcp.directauth.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.level.ServerPlayer;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PositionManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path databasePath;
    private Map<UUID, StoredLocation> positions;
    
    public PositionManager(Path worldPath) {
        this.databasePath = worldPath.resolve("serverconfig").resolve("DirectAuth_positions.json");
        this.positions = new HashMap<>();
        loadDatabase();
    }
    
    private void loadDatabase() {
        try {
            Files.createDirectories(databasePath.getParent());
            if (Files.exists(databasePath)) {
                Reader reader = Files.newBufferedReader(databasePath);
                positions = GSON.fromJson(reader, new TypeToken<Map<UUID, StoredLocation>>(){}.getType());
                reader.close();
                if (positions == null) positions = new HashMap<>();
            }
        } catch (IOException e) {
            System.err.println("Error cargando posiciones: " + e.getMessage());
            positions = new HashMap<>();
        }
    }
    
    private void saveDatabase() {
        try {
            Writer writer = Files.newBufferedWriter(databasePath);
            GSON.toJson(positions, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Error guardando posiciones: " + e.getMessage());
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
