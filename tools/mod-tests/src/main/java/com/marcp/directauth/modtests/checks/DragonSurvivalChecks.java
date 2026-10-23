package com.marcp.directauth.modtests.checks;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.AltarData;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Dragon Survival: ¿ha usado el jugador el altar (y ya no se le ofrecerá al entrar)? */
public final class DragonSurvivalChecks {
    private DragonSurvivalChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("ds-altar-used", player -> String.valueOf(AltarData.getData(player).hasUsedAltar));
    }
}
