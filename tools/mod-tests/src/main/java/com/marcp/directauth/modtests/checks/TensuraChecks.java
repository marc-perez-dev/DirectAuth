package com.marcp.directauth.modtests.checks;

import io.github.manasmods.manascore.race.api.RaceAPI;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Tensura: ¿tiene el jugador raza? */
public final class TensuraChecks {
    private TensuraChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("tensura-has-race", player -> String.valueOf(RaceAPI.getRaceFrom(player).getRace().isPresent()));
    }
}
