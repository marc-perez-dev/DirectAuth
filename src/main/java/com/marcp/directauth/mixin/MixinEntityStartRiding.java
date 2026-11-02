package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Un jugador sin autenticar no se monta (EntityMountEvent en NeoForge, también con force). */
@Mixin(Entity.class)
public abstract class MixinEntityStartRiding {

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void directAuth$noMountWhileUnauthenticated(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (PlayerRestrictionHandler.onMount(this)) {
            cir.setReturnValue(false);
        }
    }
}
