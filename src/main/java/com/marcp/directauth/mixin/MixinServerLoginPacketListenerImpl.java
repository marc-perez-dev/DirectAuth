package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import com.mojang.authlib.GameProfile;
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
    // @Shadow private GameProfile gameProfile; // Eliminado para evitar problemas de mapeo
    
    // Métodos shadow para invocar la lógica nativa
    @Shadow protected abstract void startClientVerification(GameProfile profile);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        // Si el servidor ya es nativamente premium, no interferimos
        if (this.server.usesAuthentication()) return;

        String username = packet.name();
        
        // Consultamos la DB local (rápido, en memoria)
        // Nota: Asegurarse de que DirectAuth.getDatabase() esté inicializado
        if (DirectAuth.getDatabase() == null) return;
        
        UserData data = DirectAuth.getDatabase().getUser(username);

        // Si el usuario activó el modo Premium previamente
        if (data != null && data.isPremium() && data.getOnlineUUID() != null) {
            try {
                // Forzamos el inicio de sesión seguro usando la UUID real guardada
                UUID uuid = UUID.fromString(data.getOnlineUUID());
                GameProfile premiumProfile = new GameProfile(uuid, username);
                this.startClientVerification(premiumProfile);
                
                // Cancelamos el flujo normal para evitar que entre como Offline
                ci.cancel();
            } catch (IllegalArgumentException e) {
                // Si la UUID guardada es inválida, dejamos pasar como offline (fail-safe)
                DirectAuth.LOGGER.error("Invalid UUID for premium user {}: {}", username, data.getOnlineUUID());
            }
        }
    }
}
