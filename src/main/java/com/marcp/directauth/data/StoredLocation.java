package com.marcp.directauth.data;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class StoredLocation {
    public String dimension;
    public double x, y, z;
    public float yRot, xRot;
    
    // Nuevos campos para el estado del jugador
    public float health;
    public int foodLevel;
    public float saturation;
    public int fireTicks;

    public StoredLocation(String dimension, double x, double y, double z, float yRot, float xRot, 
                          float health, int foodLevel, float saturation, int fireTicks) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
        this.health = health;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
        this.fireTicks = fireTicks;
    }

    public static StoredLocation fromPlayer(ServerPlayer player) {
        return new StoredLocation(
            player.level().dimension().location().toString(),
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getYRot(),
            player.getXRot(),
            player.getHealth(),           // Guardar Vida
            player.getFoodData().getFoodLevel(), // Guardar Hambre
            player.getFoodData().getSaturationLevel(),
            player.getRemainingFireTicks() // Guardar Fuego
        );
    }
    
    public void teleportPlayer(ServerPlayer player) {
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(this.dimension));
        ServerLevel targetLevel = player.getServer().getLevel(dimKey);
        
        if (targetLevel != null) {
            // 1. Restaurar posición
            player.teleportTo(targetLevel, this.x, this.y, this.z, this.yRot, this.xRot);
            
            // 2. Restaurar estado vital (Evita el exploit de regeneración)
            // Solo aplicamos si la salud es mayor a 0 para evitar bucles de muerte extraños,
            // aunque si murió debería haber respawneado limpio.
            if (this.health > 0) {
                player.setHealth(this.health);
            }
            player.getFoodData().setFoodLevel(this.foodLevel);
            player.getFoodData().setSaturation(this.saturation);
            
            // 3. Restaurar fuego (Si se estaba quemando, se seguirá quemando)
            if (this.fireTicks > 0) {
                player.setRemainingFireTicks(this.fireTicks);
            }
        }
    }
}
