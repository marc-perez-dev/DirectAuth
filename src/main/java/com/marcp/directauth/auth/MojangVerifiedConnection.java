package com.marcp.directauth.auth;

import java.util.UUID;

/**
 * Lo implementa {@code net.minecraft.network.Connection} (MixinConnection): la UUID de Mojang que
 * el servidor de sesión confirmó durante el login de esta conexión, o null si no pasó por Mojang.
 * La UUID del jugador no sirve para saberlo, porque una cuenta premium juega con la UUID que ya
 * tenía (ver MixinServerLoginPacketListenerImpl).
 */
public interface MojangVerifiedConnection {
    UUID directAuth$getMojangUUID();

    void directAuth$setMojangUUID(UUID uuid);
}
