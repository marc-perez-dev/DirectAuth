package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Complemento de {@link MixinPlayerListRespawn}. En 1.20.1 el jugador nuevo que crea
 * PlayerList.respawn solo recibe el punto de reaparición si se usó; como un jugador sin
 * autenticar no lo usa, se le copia aquí para que la cuenta no pierda su cama / nexo
 * (en 1.21.1 lo hace vanilla con copyRespawnPosition).
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerRespawn {

    @Shadow private ResourceKey<Level> respawnDimension;
    @Shadow private BlockPos respawnPosition;
    @Shadow private float respawnAngle;
    @Shadow private boolean respawnForced;

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void directAuth$keepRespawnPointWhileUnauthenticated(ServerPlayer that, boolean keepEverything, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(that)) {
            this.respawnDimension = that.getRespawnDimension();
            this.respawnPosition = that.getRespawnPosition();
            this.respawnAngle = that.getRespawnAngle();
            this.respawnForced = that.isRespawnForced();
        }
    }
}
