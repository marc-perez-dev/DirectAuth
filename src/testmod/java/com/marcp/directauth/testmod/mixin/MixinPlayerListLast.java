package com.marcp.directauth.testmod.mixin;

import com.marcp.directauth.testmod.DirectAuthTest;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Una mod que actúa al final de PlayerList.placeNewPlayer, después del manejador de entrada de
 * DirectAuth (también al final, en MixinPlayerList): con más prioridad, este mixin se aplica después
 * y su llamada queda detrás de la de DirectAuth.
 */
@Mixin(value = PlayerList.class, priority = 2000)
public abstract class MixinPlayerListLast {

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void directAuthTest$afterDirectAuth(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        if (player.getGameProfile().getName().startsWith("TpLast") && DirectAuthTest.neverPlayed(player)) {
            DirectAuthTest.teleportNewPlayer(player);
        }
    }
}
