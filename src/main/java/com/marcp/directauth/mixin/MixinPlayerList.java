package com.marcp.directauth.mixin;

import com.marcp.directauth.events.JailedVehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Aparta el vehículo del jugador mientras está sin autenticar (ver JailedVehicles). */
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    /** load solo lo llama placeNewPlayer, antes de que vuelva a montar al jugador en su vehículo. */
    @Inject(method = "load", at = @At("RETURN"))
    private void directAuth$stashVehicle(ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        cir.getReturnValue().ifPresent(tag -> JailedVehicles.take(player, tag));
    }

    /** remove ya lo ha guardado, con el vehículo apartado. */
    @Inject(method = "remove", at = @At("TAIL"))
    private void directAuth$forgetVehicle(ServerPlayer player, CallbackInfo ci) {
        JailedVehicles.forget(player);
    }
}
