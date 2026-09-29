package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Un jugador sin autenticar que pulsa "Reaparecer" no debe aparecer en la cama / nexo de la
 * cuenta (revelaría sus coordenadas y gastaría una carga del nexo). Reaparece en el spawn del
 * mundo; al autenticarse, LoginCommand lo lleva a su punto de reaparición.
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerRespawn {

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("HEAD"), cancellable = true)
    private void directAuth$hideRespawnPointWhileUnauthenticated(
            boolean keepInventory,
            DimensionTransition.PostDimensionTransition postDimensionTransition,
            CallbackInfoReturnable<DimensionTransition> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (PlayerRestrictionHandler.isRestricted(self)) {
            cir.setReturnValue(new DimensionTransition(self.getServer().overworld(), self, postDimensionTransition));
        }
    }
}
