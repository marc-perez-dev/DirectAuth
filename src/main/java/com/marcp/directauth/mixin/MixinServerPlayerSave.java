package com.marcp.directauth.mixin;

import com.marcp.directauth.events.JailedVehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cada guardado del jugador conserva el vehículo apartado mientras está sin autenticar (JailedVehicles). */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerSave {

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void directAuth$writeBackVehicle(CompoundTag tag, CallbackInfo ci) {
        JailedVehicles.writeBack((ServerPlayer) (Object) this, tag);
    }
}
