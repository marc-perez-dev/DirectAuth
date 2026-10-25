package com.marcp.directauth.mixin;

import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paquetes de otras mods de un jugador sin autenticar (ver JailTraffic):
 * - los que envía su cliente se retienen justo antes de que NeoForge se los pase a la mod (hilo de
 *   red); los de Minecraft y NeoForge (registro de canales...) se procesan antes y no llegan aquí.
 * - las órdenes de abrir pantallas que el servidor le envía se aplazan, después de que NeoForge
 *   compruebe el canal (un canal no declarado sigue fallando en el sitio de siempre).
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MixinServerCommonPacketListenerImpl {

    @Inject(method = "handleCustomPayload",
        at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/registration/NetworkRegistry;handleModdedPayload(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V"),
        cancellable = true)
    private void directAuth$holdModPayloadWhileUnauthenticated(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && JailTraffic.holdFromClient(game, packet)) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
        at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/network/registration/NetworkRegistry;checkPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V", shift = At.Shift.AFTER),
        cancellable = true)
    private void directAuth$deferScreenPayloadWhileUnauthenticated(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if ((Object) this instanceof ServerGamePacketListenerImpl game && JailTraffic.deferToClient(game, packet, listener)) {
            ci.cancel();
        }
    }
}
