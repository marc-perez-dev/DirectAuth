package com.marcp.directauth.mixin;

import com.marcp.directauth.events.ConnectionHandler;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entrada y salida de jugadores, en los mismos puntos que PlayerLoggedInEvent y
 * PlayerLoggedOutEvent de NeoForge: al final de placeNewPlayer (el jugador ya está en el mundo y
 * en la lista de jugadores) y al principio de remove. ServerPlayConnectionEvents.JOIN de Fabric
 * API se dispara antes, a mitad de placeNewPlayer.
 * Mientras dura placeNewPlayer, el jugador puede volver a montar en su vehículo (ver
 * MixinEntityStartRiding).
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void directAuth$startPlacing(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerRestrictionHandler.setBeingPlaced(player);
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void directAuth$onPlayerLoggedIn(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerRestrictionHandler.setBeingPlaced(null);
        ConnectionHandler.onPlayerLogin(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void directAuth$onPlayerLoggedOut(ServerPlayer player, CallbackInfo ci) {
        ConnectionHandler.onPlayerLogout(player);
    }
}
