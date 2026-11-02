package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Un jugador sin autenticar no pierde vida ni corazones de absorción. Como LivingDamageEvent.Pre
 * de NeoForge: el daño se anula ya reducido por armadura y efectos (la armadura se desgasta como
 * siempre) y antes de restarlo de la absorción. ServerLivingEntityEvents.ALLOW_DAMAGE de Fabric
 * API cancelaría el golpe entero (sin empuje, ni animación, ni efectos del atacante).
 */
@Mixin(Player.class)
public abstract class MixinPlayerDamage {

    @ModifyVariable(method = "actuallyHurt",
        at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/world/entity/player/Player;getDamageAfterMagicAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"),
        argsOnly = true)
    private float directAuth$noDamageWhileUnauthenticated(float damage) {
        return PlayerRestrictionHandler.onDamage(this, damage);
    }
}
