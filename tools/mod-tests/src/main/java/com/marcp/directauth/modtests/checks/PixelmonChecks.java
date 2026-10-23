package com.marcp.directauth.modtests.checks;

import com.pixelmonmod.pixelmon.api.storage.StorageProxy;
import com.pixelmonmod.pixelmon.config.starter.StarterList;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Pixelmon: ¿ha elegido el jugador inicial? y cuántas opciones espera el paquete de elegirlo. */
public final class PixelmonChecks {
    private PixelmonChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("pixelmon-starter-picked", player -> String.valueOf(StorageProxy.getPartyNow(player).starterPicked));
        checks.put("pixelmon-starter-options", player -> String.valueOf(StarterList.getOptions().size()));
    }
}
