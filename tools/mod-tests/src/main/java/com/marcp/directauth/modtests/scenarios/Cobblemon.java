package com.marcp.directauth.modtests.scenarios;

import com.cobblemon.mod.common.api.storage.party.PartyPosition;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.net.messages.server.SelectStarterPacket;
import com.cobblemon.mod.common.net.messages.server.storage.party.ReleasePartyPokemonPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.marcp.directauth.modtests.ClientDriver;

import java.util.UUID;

/**
 * Cobblemon no fuerza pantallas, pero acepta desde la jaula elegir inicial y liberar Pokémon: el
 * cliente ya tiene el equipo (posiciones y UUIDs) aunque el jugador no se haya autenticado.
 */
public class Cobblemon implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNew(d);
        Scenario.logout(d);

        // Un impostor elige el inicial de una cuenta que aún no lo tiene
        Scenario.joinJailed(d);
        d.sleep(2000);
        selectStarter(d);
        d.sleep(2000);
        d.check("starter chosen from the jail is not applied", Scenario.mt(d, "cobblemon-starter-selected").equals("false"), Scenario.mt(d, "cobblemon-starter-selected"));
        d.disconnect();

        // El dueño elige inicial (control) y recibe otro Pokémon: el equipo no puede quedarse vacío
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no starter after the impostor left", Scenario.mt(d, "cobblemon-starter-selected").equals("false"), Scenario.mt(d, "cobblemon-starter-selected"));
        selectStarter(d);
        d.sleep(2000);
        d.check("control: starter chosen after /login", Scenario.mt(d, "cobblemon-party-size").equals("1"), "party size " + Scenario.mt(d, "cobblemon-party-size"));
        d.rcon("pokegiveother " + d.mc().getUser().getName() + " pikachu");
        d.require("party has two Pokémon", Scenario.waitMt(d, "cobblemon-party-size", "2", 5000), "party size " + Scenario.mt(d, "cobblemon-party-size"));
        Scenario.logout(d);

        // Un impostor libera un Pokémon de la cuenta
        Scenario.joinJailed(d);
        d.require("the jailed client knows the party (positions and UUIDs)", d.waitFor(() -> firstPokemon() != null, 10_000), "client party empty");
        release(d);
        d.sleep(2000);
        d.check("Pokémon released from the jail is kept", Scenario.mt(d, "cobblemon-party-size").equals("2"), "party size " + Scenario.mt(d, "cobblemon-party-size"));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("party intact after the impostor left", Scenario.mt(d, "cobblemon-party-size").equals("2"), "party size " + Scenario.mt(d, "cobblemon-party-size"));
        d.require("client knows the party after /login", d.waitFor(() -> firstPokemon() != null, 10_000), "client party empty");
        release(d);
        d.sleep(2000);
        d.check("control: release works after /login", Scenario.mt(d, "cobblemon-party-size").equals("1"), "party size " + Scenario.mt(d, "cobblemon-party-size"));
        d.disconnect();
    }

    /** Primera ranura ocupada del equipo que conoce el cliente (-1 si está vacío). */
    private static int firstSlot() {
        var slots = CobblemonClient.INSTANCE.getStorage().getParty().getSlots();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) != null) return i;
        }
        return -1;
    }

    private static Pokemon firstPokemon() {
        int slot = firstSlot();
        return slot < 0 ? null : CobblemonClient.INSTANCE.getStorage().getParty().getSlots().get(slot);
    }

    private static void selectStarter(ClientDriver d) throws Exception {
        String category = Scenario.mt(d, "cobblemon-first-category");
        d.onClientRun(() -> new SelectStarterPacket(category, 0).sendToServer());
    }

    /** Lo que envía el botón "liberar" del resumen del equipo. */
    private static void release(ClientDriver d) throws Exception {
        int slot = d.onClient(Cobblemon::firstSlot);
        UUID uuid = d.onClient(() -> firstPokemon().getUuid());
        d.onClientRun(() -> new ReleasePartyPokemonPacket(uuid, new PartyPosition(slot)).sendToServer());
    }
}
