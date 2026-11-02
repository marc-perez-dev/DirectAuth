package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un jugador sin autenticar no recoge experiencia (PlayerXpEvent.PickupXp en NeoForge). */
@Mixin(ExperienceOrb.class)
public abstract class MixinExperienceOrbPickup {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void directAuth$noXpPickupWhileUnauthenticated(Player player, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(player)) {
            ci.cancel();
        }
    }
}
