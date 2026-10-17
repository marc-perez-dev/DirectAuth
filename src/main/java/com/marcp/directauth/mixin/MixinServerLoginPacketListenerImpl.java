package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.data.UserData;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
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
    @Shadow public GameProfile gameProfile;

    // Bandera para evitar bucles infinitos (se escribe en el hilo del servidor y se lee en el de red)
    @Unique
    private volatile boolean directAuth$isDataPreloaded = false;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Si el servidor es premium nativo, ignoramos
        if (this.server.usesAuthentication()) return;

        String username = packet.name();

        // Safety check por si la DB no cargó
        if (DirectAuth.getDatabase() == null || DirectAuth.getLoginManager() == null) return;

        // --- FASE 1: Si no hemos cargado los datos, PAUSAMOS el login ---
        if (!this.directAuth$isDataPreloaded) {
            // 1. Cancelamos el procesamiento normal (El jugador se queda "esperando")
            ci.cancel();

            // 2. Iniciamos la búsqueda asíncrona (Hilo de la DB)
            DirectAuth.getDatabase().getUserAsync(username).whenCompleteAsync((data, error) -> {
                if (error != null) {
                    DirectAuth.LOGGER.error("DirectAuth: Could not load account data for {}", username, error);
                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errInternal));
                    return;
                }

                // 3. Guardamos en caché (incluido el marcador de "no existe")
                DirectAuth.getLoginManager().addPreLoadedData(username, data);

                // 4. Marcamos que ya tenemos los datos
                this.directAuth$isDataPreloaded = true;

                // 5. REINICIAMOS el proceso: Llamamos a handleHello de nuevo.
                // Como ahora la bandera es true, pasará a la FASE 2.
                // Las validaciones de vanilla (nombre inválido, paquete duplicado) lanzan aquí
                // dentro del future; sin este catch la excepción se perdería y la conexión
                // se quedaría colgada hasta el timeout de login.
                try {
                    this.handleHello(packet);
                } catch (RuntimeException e) {
                    DirectAuth.LOGGER.warn("DirectAuth: Rejected login of {}: {}", username, e.getMessage());
                    this.disconnect(Component.literal(DirectAuth.getConfig().getLang().errInternal));
                }

            }, AuthTasks.onServerThread(this.server)); // <- IMPORTANTE: Ejecutar el callback en el hilo del servidor

            return; // Salimos y esperamos al futuro
        }

        // --- FASE 2: Ya tenemos datos (Ejecución Síncrona pero Rápida) ---
        // Leemos de la caché SIN quitarlos: ConnectionHandler los consumirá al entrar al mundo.
        // Un usuario no registrado se queda como marcador en la caché, así que nunca hace falta
        // volver a leer la DB en el hilo del servidor.
        UserData data = DirectAuth.getLoginManager().peekPreLoadedData(username);

        // Lógica de Premium (Handshake de encriptación)
        if (data != null && data.isPremium() && data.getOnlineUUID() != null) {
            try {
                // Lo mismo que hace vanilla en handleHello con online-mode=true: handleKey lee
                // el nombre de este perfil para verificar la sesión con Mojang
                this.gameProfile = new GameProfile(null, username);
                this.state = ServerLoginPacketListenerImpl.State.KEY;

                this.connection.send(new ClientboundHelloPacket(
                    "", // Server ID vacío usualmente
                    this.server.getKeyPair().getPublic().getEncoded(),
                    this.challenge
                ));

                ci.cancel(); // Cancelamos para que Vanilla no intente hacer su propia lógica offline

            } catch (Exception e) {
                DirectAuth.LOGGER.error("DirectAuth: Premium handshake error: {}", e.getMessage());
            }
        }
    }

    // Shadow del método handleHello para poder llamarlo recursivamente
    @Shadow
    public abstract void handleHello(ServerboundHelloPacket p_10047_);

    @Shadow
    public abstract void disconnect(Component reason);
}
