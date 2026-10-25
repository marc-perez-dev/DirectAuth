package com.marcp.directauth.mixin;

import com.marcp.directauth.auth.MojangVerifiedConnection;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.network.JailPackets;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Descarta los paquetes vanilla que un jugador sin autenticar no debe poder enviar (ver
 * JailPackets). Algunos comprueban los permisos por su cuenta (bloques de comandos, dificultad...),
 * y quien entra con el nombre de un op los tiene aunque no haya hecho /login.
 * genericsFtw es el punto por el que pasa cada paquete recibido, en el hilo de red.
 * También guarda qué UUID de Mojang verificó el login de la conexión (MojangVerifiedConnection).
 */
@Mixin(Connection.class)
public abstract class MixinConnection implements MojangVerifiedConnection {

    // Se escribe en el hilo de autenticación del login y se lee en el del servidor
    @Unique
    private volatile UUID directAuth$mojangUUID;

    @Override
    public UUID directAuth$getMojangUUID() {
        return this.directAuth$mojangUUID;
    }

    @Override
    public void directAuth$setMojangUUID(UUID uuid) {
        this.directAuth$mojangUUID = uuid;
    }

    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static void directAuth$dropPacketWhileUnauthenticated(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (listener instanceof ServerGamePacketListenerImpl game && PlayerRestrictionHandler.isRestricted(game.player)) {
            if (packet instanceof ServerboundChatPacket chat) {
                JailPackets.dropChat(game, chat);
                ci.cancel();
            } else if (!JailPackets.allowed(packet)) {
                ci.cancel();
            }
        }
    }
}
