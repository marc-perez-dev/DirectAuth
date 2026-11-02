package com.marcp.directauth.mixin;

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
 * Marca al jugador mientras PlayerList.placeNewPlayer lo coloca: ahí vuelve a montar con force el
 * vehículo en el que se desconectó ("RootVehicle"), y siempre está sin autenticar. Si
 * EntityMountEvent se lo impidiera, vanilla eliminaría el vehículo y sus pasajeros ("Couldn't
 * reattach entity to player"). Ver PlayerRestrictionHandler.onMount.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void directAuth$startPlacing(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerRestrictionHandler.setBeingPlaced(player);
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void directAuth$endPlacing(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        PlayerRestrictionHandler.setBeingPlaced(null);
    }
}
