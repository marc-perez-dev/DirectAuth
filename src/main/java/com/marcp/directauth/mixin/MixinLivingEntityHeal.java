package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un jugador sin autenticar no se cura (LivingHealEvent en NeoForge). */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityHeal {

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void directAuth$noHealWhileUnauthenticated(float healAmount, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(this)) {
            ci.cancel();
        }
    }
}
