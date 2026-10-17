package com.marcp.directauth.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerPlayer.class)
public interface ServerPlayerAccessor {
    // Colocación de vanilla en el spawn del mundo (privada en 1.20.1; en 1.21.1 es adjustSpawnLocation)
    @Invoker("fudgeSpawnLocation")
    void callFudgeSpawnLocation(ServerLevel level);
}
