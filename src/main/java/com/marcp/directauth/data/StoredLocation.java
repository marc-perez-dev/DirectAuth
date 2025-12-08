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

    public StoredLocation(String dimension, double x, double y, double z, float yRot, float xRot) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yRot = yRot;
        this.xRot = xRot;
    }

    public static StoredLocation fromPlayer(ServerPlayer player) {
        return new StoredLocation(
            player.level().dimension().location().toString(),
            player.getX(),
            player.getY(),
            player.getZ(),
            player.getYRot(),
            player.getXRot()
        );
    }
    
    public void teleportPlayer(ServerPlayer player) {
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(this.dimension));
        ServerLevel targetLevel = player.getServer().getLevel(dimKey);
        
        if (targetLevel != null) {
            player.teleportTo(targetLevel, this.x, this.y, this.z, this.yRot, this.xRot);
        }
    }
}
