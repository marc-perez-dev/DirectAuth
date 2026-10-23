package com.marcp.directauth.modtests.checks;

import net.conczin.mca.server.world.data.PlayerSaveData;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** MCA Reborn: ¿ha completado el jugador Destiny (datos de personaje guardados)? */
public final class McaChecks {
    private McaChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("mca-destiny-done", player -> String.valueOf(PlayerSaveData.get(player).isEntityDataSet()));
    }
}
