package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compatibilidad con ItemPhysic: con "throw.enabled" el cliente no manda la Q como
 * ServerboundPlayerActionPacket sino como DropPacket por la red de CreativeCore, que suelta el
 * objeto sin pasar por handlePlayerAction (ver MixinServerGamePacketListenerImpl). PickupPacket
 * recoge objetos sin EntityItemPickupEvent. Ambos se bloquean para jugadores sin autenticar.
 *
 * @Pseudo + require = 0: sin ItemPhysic instalado el mixin simplemente no se aplica. Los métodos
 * son del mod (no se remapean); CreativeCore los ejecuta en el hilo del servidor (enqueueWork).
 */
@Pseudo
@Mixin(targets = {
    "team.creative.itemphysic.common.packet.DropPacket",
    "team.creative.itemphysic.common.packet.PickupPacket"
}, remap = false)
public abstract class MixinItemPhysicPackets {

    @Inject(method = "executeServer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void directAuth$blockWhileUnauthenticated(ServerPlayer player, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(player)) {
            ci.cancel();
            // El cliente puede haber quitado ya el objeto de su vista: reenviarle el inventario real
            player.containerMenu.sendAllDataToRemote();
            if (this.getClass().getName().endsWith("DropPacket")) {
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgNoDrop));
            }
        }
    }
}
