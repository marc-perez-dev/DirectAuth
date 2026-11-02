package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Un jugador sin autenticar no ataca, venga de donde venga la llamada (AttackEntityEvent en
 * NeoForge, en Player.attack). AttackEntityCallback de Fabric API solo cubre el paquete de
 * interactuar y se dispara antes de que ServerPlayer.attack mire el modo espectador. Aquí, como en
 * main, un espectador en la jaula sigue pudiendo pasar a la cámara de otra entidad.
 */
@Mixin(Player.class)
public abstract class MixinPlayerAttack {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void directAuth$noAttackWhileUnauthenticated(Entity target, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(this)) {
            ci.cancel();
        }
    }
}
