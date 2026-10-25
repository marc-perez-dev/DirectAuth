package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/**
 * The Aether con spawn_in_aether: en la primera entrada lleva al jugador al Aether y gasta la marca
 * de "primera vez". Debe acabar en el Aether aunque DirectAuth lo meta antes en la jaula.
 */
public class Aether implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.joinNew(d);
        d.sleep(1000);
        String jailed = where(d);
        d.check("new player is in the Aether while jailed", jailed.contains("aether:the_aether"), jailed);
        Scenario.register(d);
        // Dónde está durante los segundos siguientes (Aether lo deja caer en paracaídas)
        StringBuilder trail = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            trail.append(where(d)).append("; ");
            d.sleep(500);
        }
        String after = where(d);
        d.check("new player is still in the Aether after /register", after.contains("aether:the_aether"), trail.toString());
        d.disconnect();
    }

    private static String where(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();
        String dimension = d.rcon("data get entity " + player + " Dimension").replaceAll(".*: ", "").trim();
        String pos = d.rcon("data get entity " + player + " Pos").replaceAll(".*: ", "").trim();
        return dimension + " " + pos;
    }
}
