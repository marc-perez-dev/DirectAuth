package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un jugador sin autenticar no recoge objetos (ItemEntityPickupEvent.Pre en NeoForge). */
@Mixin(ItemEntity.class)
public abstract class MixinItemEntityPickup {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void directAuth$noPickupWhileUnauthenticated(Player player, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(player)) {
            ci.cancel();
        }
    }
}
