package com.marcp.directauth.mixin;

import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paquetes de otras mods que envía el cliente de un jugador sin autenticar (ver JailTraffic): se
 * retienen antes de que Forge se los pase a la mod (NetworkHooks.onCustomPayload, hilo de red).
 * Los de Minecraft y Forge (registro de canales...) siguen su curso.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerCustomPayload {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void directAuth$holdModPayloadWhileUnauthenticated(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (JailTraffic.holdFromClient((ServerGamePacketListenerImpl) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
