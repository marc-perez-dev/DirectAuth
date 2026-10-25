package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.network.JailPackets;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Descarta los paquetes vanilla que un jugador sin autenticar no debe poder enviar (ver
 * JailPackets). Algunos comprueban los permisos por su cuenta (bloques de comandos, dificultad...),
 * y quien entra con el nombre de un op los tiene aunque no haya hecho /login.
 * genericsFtw es el punto por el que pasa cada paquete recibido, en el hilo de red.
 */
@Mixin(Connection.class)
public abstract class MixinConnection {

    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static void directAuth$dropPacketWhileUnauthenticated(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (listener instanceof ServerGamePacketListenerImpl game
                && PlayerRestrictionHandler.isRestricted(game.player)
                && !JailPackets.allowed(packet)) {
            ci.cancel();
        }
    }
}
