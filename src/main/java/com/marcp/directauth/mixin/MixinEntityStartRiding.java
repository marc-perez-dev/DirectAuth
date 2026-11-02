package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Un jugador sin autenticar no se monta (EntityMountEvent en NeoForge, también con force).
 * Excepto mientras PlayerList.placeNewPlayer lo coloca: ahí vuelve a montar con force el vehículo
 * en el que se desconectó ("RootVehicle"), y siempre está sin autenticar. Si se le impidiera,
 * vanilla eliminaría el vehículo y sus pasajeros ("Couldn't reattach entity to player"). Después,
 * la jaula lo desmonta (el teleport al spawn de ConnectionHandler o PlayerRestrictionHandler) y el
 * vehículo se queda donde estaba.
 */
@Mixin(Entity.class)
public abstract class MixinEntityStartRiding {

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void directAuth$noMountWhileUnauthenticated(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (PlayerRestrictionHandler.isRestricted(this) && !PlayerRestrictionHandler.isBeingPlaced(this)) {
            cir.setReturnValue(false);
        }
    }
}
