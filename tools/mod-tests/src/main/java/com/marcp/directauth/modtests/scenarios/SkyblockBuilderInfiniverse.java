package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/** Skyblock Builder con una dimensión por equipo (Infiniverse): la isla inicial está en su propia dimensión. */
public class SkyblockBuilderInfiniverse implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        SkyblockBuilder.checkStartsOnIsland(d, "skyblockbuilder:spawn");
    }
}
