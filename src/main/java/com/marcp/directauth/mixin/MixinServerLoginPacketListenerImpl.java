package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    
    // CORRECCIÓN 1: 'nonce' -> 'challenge'
    @Shadow private byte[] challenge; 
    
    // CORRECCIÓN 2: 'gameProfile' -> 'authenticatedProfile'
    @Shadow private GameProfile authenticatedProfile;

    @Shadow @Final private String serverId;
    @Shadow private ServerLoginPacketListenerImpl.State state;
    
    // NUEVO CAMPO NECESARIO: Aquí se guarda el nombre temporalmente
    @Shadow private String requestedUsername;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Si el servidor ya es nativamente premium, no interferimos
        if (this.server.usesAuthentication()) return;

        String username = packet.name();
        
        if (DirectAuth.getDatabase() == null) return;
        
        UserData data = DirectAuth.getDatabase().getUser(username);

        // Si el usuario es Premium en nuestra DB
        if (data != null && data.isPremium() && data.getOnlineUUID() != null) {
            try {
                // 1. Inicializamos el nombre de usuario (CRÍTICO para evitar el NPE)
                this.requestedUsername = username;
                
                // 2. Preparamos el estado para esperar la clave de encriptación
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                
                // 3. Enviamos el reto de encriptación
                this.connection.send(new ClientboundHelloPacket(
                    this.serverId, 
                    this.server.getKeyPair().getPublic().getEncoded(), 
                    this.challenge, 
                    true
                ));
                
                // 4. Cancelamos el flujo offline
                ci.cancel();
                
            } catch (Exception e) {
                DirectAuth.LOGGER.error("Error iniciando handshake premium para {}: {}", username, e.getMessage());
                // En caso de error, dejamos que fluya (no cancelamos) para que entre offline como fallback
                // o desconectamos manualmente si prefieres seguridad estricta.
            }
        }
    }
}
