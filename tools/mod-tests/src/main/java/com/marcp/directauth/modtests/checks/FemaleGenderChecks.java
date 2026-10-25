package com.marcp.directauth.modtests.checks;

import com.wildfire.main.GenderPlayer;
import com.wildfire.main.WildfireGender;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Female Gender: género que el cliente del jugador ha sincronizado con el servidor. */
public final class FemaleGenderChecks {
    private FemaleGenderChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("wildfire-gender", player -> {
            GenderPlayer plr = WildfireGender.getPlayerById(player.getUUID());
            return plr == null ? "none" : plr.getGender().name();
        });
    }
}
