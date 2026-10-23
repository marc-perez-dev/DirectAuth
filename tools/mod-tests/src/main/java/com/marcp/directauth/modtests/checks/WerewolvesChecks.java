package com.marcp.directauth.modtests.checks;

import de.teamlapen.werewolves.api.entities.werewolf.WerewolfForm;
import de.teamlapen.werewolves.core.ModActions;
import de.teamlapen.werewolves.entities.player.werewolf.WerewolfPlayer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/**
 * Werewolves: los jugadores pasan a forma bestia con una acción; las pruebas la ponen desde el
 * servidor ({@code werewolves-make-beast}) para comprobar si el jugador puede morder.
 */
public final class WerewolvesChecks {
    private WerewolvesChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("werewolves-make-beast", player -> {
            WerewolfPlayer werewolf = WerewolfPlayer.get(player);
            werewolf.setForm(ModActions.BEAST_FORM.get(), WerewolfForm.BEAST);
            return String.valueOf(werewolf.canBite());
        });
    }
}
