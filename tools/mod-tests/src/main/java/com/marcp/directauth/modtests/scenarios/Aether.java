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
        Scenario.registerNew(d);
        d.sleep(2000);
        String dimension = d.rcon("data get entity " + player + " Dimension");
        d.check("new player starts in the Aether", dimension.contains("aether:the_aether"), dimension);
        d.disconnect();
    }
}
