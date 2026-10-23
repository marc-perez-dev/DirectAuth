package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/**
 * Skyblock Builder lleva a los jugadores nuevos a la isla inicial. En un mundo skyblock alrededor
 * solo hay vacío: el jugador debe acabar en la isla, no cayendo.
 */
public class SkyblockBuilder implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        checkStartsOnIsland(d, "minecraft:overworld");
    }

    static void checkStartsOnIsland(ClientDriver d, String expectedDimension) throws Exception {
        String player = d.mc().getUser().getName();
        Scenario.registerNew(d);
        d.sleep(5000);
        String dimension = d.rcon("data get entity " + player + " Dimension");
        d.check("new player is in " + expectedDimension, dimension.contains(expectedDimension), dimension);
        d.check("new player stands on the island (not falling, alive)", onGround(d) && d.onClient(() -> d.mc().player.isAlive()),
            "on ground: " + onGround(d) + ", pos: " + d.onClient(() -> d.mc().player.position().toString()));
        d.disconnect();
    }

    private static boolean onGround(ClientDriver d) throws Exception {
        return d.onClient(() -> d.mc().player.onGround());
    }
}
