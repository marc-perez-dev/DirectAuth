package com.marcp.directauth.modtests.checks;

import net.minecraft.server.level.ServerPlayer;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

import java.util.Map;
import java.util.function.Function;

/** Epic Fight: modo del jugador en el servidor (vanilla o epicfight). */
public final class EpicFightChecks {
    private EpicFightChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("epicfight-mode", player -> EpicFightCapabilities.getUnparameterizedEntityPatch(player, ServerPlayerPatch.class)
            .map(patch -> patch.isEpicFightMode() ? "epicfight" : "vanilla").orElse("none"));
    }
}
