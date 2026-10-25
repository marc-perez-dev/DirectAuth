package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/**
 * The Aether con spawn_in_aether: en la primera entrada lleva al jugador al Aether y gasta la marca
 * de "primera vez". Debe acabar en el Aether aunque DirectAuth lo meta antes en la jaula.
 */
public class Aether implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();
        Scenario.joinNew(d);
        d.sleep(1000);
        String jailed = where(d, player);
        d.check("new player is in the Aether while jailed", jailed.contains("aether:the_aether"), jailed);
        Scenario.register(d);
        // Aether lo deja caer en paracaídas: debe seguir en el Aether durante toda la caída
        StringBuilder trail = new StringBuilder();
        boolean stayed = true;
        for (int i = 0; i < 10; i++) {
            String sample = where(d, player);
            trail.append(sample).append("; ");
            stayed &= sample.contains("aether:the_aether");
            d.sleep(1000);
        }
        d.check("new player stays in the Aether after /register", stayed, trail.toString());
        d.disconnect();
    }

    private static String where(ClientDriver d, String player) throws Exception {
        String dimension = d.rcon("data get entity " + player + " Dimension").replaceAll(".*: ", "").trim();
        String pos = d.rcon("data get entity " + player + " Pos").replaceAll(".*: ", "").trim();
        return dimension + " " + pos;
    }
}
