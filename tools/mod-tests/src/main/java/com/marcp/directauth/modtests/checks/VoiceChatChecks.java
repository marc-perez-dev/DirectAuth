package com.marcp.directauth.modtests.checks;

import de.maxhenkel.voicechat.Voicechat;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Simple Voice Chat: ¿ha generado el servidor la clave de voz del jugador (la pide el cliente)? */
public final class VoiceChatChecks {
    private VoiceChatChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("voicechat-has-secret", player -> {
            var server = Voicechat.SERVER == null ? null : Voicechat.SERVER.getServer();
            return String.valueOf(server != null && server.hasSecret(player.getUUID()));
        });
    }
}
