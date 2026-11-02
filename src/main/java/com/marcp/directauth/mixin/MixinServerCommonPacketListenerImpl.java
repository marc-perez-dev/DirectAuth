package com.marcp.directauth.mixin;

import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Las órdenes de abrir pantallas de otras mods que el servidor envía a un jugador sin autenticar se
 * aplazan (ver JailTraffic). ServerPlayNetworking.send de Fabric API y los envíos de vanilla pasan
 * por aquí. Los paquetes de otras mods que envía el cliente se retienen en MixinConnection.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListenerImpl {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At("HEAD"), cancellable = true)
    private void directAuth$deferScreenPayloadWhileUnauthenticated(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && JailTraffic.deferToClient(game, packet, listener)) {
            ci.cancel();
        }
    }
}
