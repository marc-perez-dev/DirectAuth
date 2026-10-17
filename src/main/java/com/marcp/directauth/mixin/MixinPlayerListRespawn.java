package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Un jugador sin autenticar que pulsa "Reaparecer" no debe aparecer en la cama / nexo de la
 * cuenta (revelaría sus coordenadas y gastaría una carga del nexo). Reaparece en el spawn del
 * mundo; al autenticarse, LoginCommand lo lleva a su punto de reaparición.
 *
 * En 1.20.1 la búsqueda del punto de reaparición está dentro de PlayerList.respawn: se hace como
 * si la cuenta no tuviera punto de reaparición (sin buscarlo, sin gastar carga y sin el aviso de
 * "no tienes cama"). {@link MixinServerPlayerRespawn} conserva el punto en el jugador nuevo.
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListRespawn {

    @ModifyVariable(method = "respawn", at = @At("STORE"), ordinal = 0)
    private BlockPos directAuth$hideRespawnPointWhileUnauthenticated(BlockPos respawnPosition, ServerPlayer player, boolean keepEverything) {
        return PlayerRestrictionHandler.isRestricted(player) ? null : respawnPosition;
    }
}
