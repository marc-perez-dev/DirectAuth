package com.marcp.directauth.mixin;

import com.marcp.directauth.events.ConnectionHandler;
import com.marcp.directauth.events.JailedVehicles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Entrada y salida de jugadores, en los mismos puntos que PlayerLoggedInEvent y
 * PlayerLoggedOutEvent de NeoForge: al final de placeNewPlayer (el jugador ya está en el mundo y
 * en la lista de jugadores) y al principio de remove. ServerPlayConnectionEvents.JOIN de Fabric
 * API se dispara antes, a mitad de placeNewPlayer.
 * Además aparta el vehículo del jugador mientras está sin autenticar (ver JailedVehicles).
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    /** load solo lo llama placeNewPlayer, antes de que vuelva a montar al jugador en su vehículo. */
    @Inject(method = "load", at = @At("RETURN"))
    private void directAuth$stashVehicle(ServerPlayer player, CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        cir.getReturnValue().ifPresent(tag -> JailedVehicles.take(player, tag));
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void directAuth$onPlayerLoggedIn(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        ConnectionHandler.onPlayerLogin(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void directAuth$onPlayerLoggedOut(ServerPlayer player, CallbackInfo ci) {
        ConnectionHandler.onPlayerLogout(player);
    }

    /** remove ya lo ha guardado, con el vehículo apartado. */
    @Inject(method = "remove", at = @At("TAIL"))
    private void directAuth$forgetVehicle(ServerPlayer player, CallbackInfo ci) {
        JailedVehicles.forget(player);
    }
}
