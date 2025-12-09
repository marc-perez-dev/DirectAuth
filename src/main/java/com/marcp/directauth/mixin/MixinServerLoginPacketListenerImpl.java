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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class MixinServerLoginPacketListenerImpl {

    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow private byte[] challenge;
    @Shadow private ServerLoginPacketListenerImpl.State state;
    @Shadow private String requestedUsername;

    // --- NUEVO: Bandera para evitar bucles infinitos ---
    @Unique
    private boolean directAuth$isDataPreloaded = false;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Si el servidor es premium nativo, ignoramos
        if (this.server.usesAuthentication()) return;

        String username = packet.name();
        
        // Safety check por si la DB no cargó
        if (DirectAuth.getDatabase() == null) return;

        // --- FASE 1: Si no hemos cargado los datos, PAUSAMOS el login ---
        if (!this.directAuth$isDataPreloaded) {
            // 1. Cancelamos el procesamiento normal (El jugador se queda "esperando")
            ci.cancel();

            // 2. Iniciamos la búsqueda asíncrona (Hilo secundario)
            DirectAuth.getDatabase().getUserAsync(username).thenAcceptAsync(data -> {
                
                // 3. Cuando termina, guardamos en caché (Hilo principal gracias a thenAcceptAsync...server)
                if (DirectAuth.getLoginManager() != null) {
                    DirectAuth.getLoginManager().addPreLoadedData(username, data);
                }

                // 4. Marcamos que ya tenemos los datos
                this.directAuth$isDataPreloaded = true;

                // 5. REINICIAMOS el proceso: Llamamos a handleHello de nuevo.
                // Como ahora la bandera es true, pasará a la FASE 2.
                this.handleHello(packet);

            }, this.server); // <- IMPORTANTE: Ejecutar el callback en el hilo del servidor
            
            return; // Salimos y esperamos al futuro
        }

        // --- FASE 2: Ya tenemos datos (Ejecución Síncrona pero Rápida) ---
        
        // Intentamos sacar los datos de la MEMORIA (Caché), no del disco
        UserData data = null;
        if (DirectAuth.getLoginManager() != null) {
            // Truco: Recuperamos el dato que acabamos de poner en caché
             data = DirectAuth.getLoginManager().getAndRemovePreLoadedData(username);
             
             // Lo volvemos a poner porque ConnectionHandler lo necesitará luego al entrar al mundo
             if (data != null) DirectAuth.getLoginManager().addPreLoadedData(username, data);
        }

        // Fallback: Si falló la caché, leemos disco (no debería pasar si Fase 1 funcionó)
        if (data == null) {
            data = DirectAuth.getDatabase().getUser(username);
        }

        // Lógica de Premium (Handshake de encriptación)
        if (data != null && data.isPremium() && data.getOnlineUUID() != null) {
            try {
                this.requestedUsername = username;
                this.state = ServerLoginPacketListenerImpl.State.KEY;
                
                this.connection.send(new ClientboundHelloPacket(
                    "", // Server ID vacío usualmente
                    this.server.getKeyPair().getPublic().getEncoded(), 
                    this.challenge, 
                    true
                ));
                
                ci.cancel(); // Cancelamos para que Vanilla no intente hacer su propia lógica offline
                
            } catch (Exception e) {
                DirectAuth.LOGGER.error("Error handshake premium: {}", e.getMessage());
            }
        }
    }
    
    // Shadow del método handleHello para poder llamarlo recursivamente
    @Shadow
    public abstract void handleHello(ServerboundHelloPacket p_10047_);
}