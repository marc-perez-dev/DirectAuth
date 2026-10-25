package com.marcp.directauth.modtests.checks;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.PlayerExtensionsKt;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Cobblemon: Pokémon en el equipo, si ha elegido inicial y nombre de la primera categoría de iniciales. */
public final class CobblemonChecks {
    private CobblemonChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("cobblemon-party-size", player -> {
            int size = 0;
            for (Pokemon pokemon : PlayerExtensionsKt.party(player)) {
                if (pokemon != null) size++;
            }
            return String.valueOf(size);
        });
        checks.put("cobblemon-starter-selected",
            player -> String.valueOf(Cobblemon.INSTANCE.getPlayerData().get(player).getStarterSelected()));
        checks.put("cobblemon-first-category",
            player -> Cobblemon.INSTANCE.getStarterConfig().getStarters().get(0).getName());
    }
}
