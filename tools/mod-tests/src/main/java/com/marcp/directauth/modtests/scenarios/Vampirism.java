package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import de.teamlapen.vampirism.network.ServerboundStartFeedingPacket;

/** Vampirism: un vampiro muerde a una entidad cercana con ServerboundStartFeedingPacket. */
public class Vampirism implements Scenario {
    private static final String BITTEN = "vampirism:amount_bitten";

    @Override
    public void run(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();
        Scenario.registerNew(d);
        d.rcon("vampirism level vampirism:vampire 1 " + player);
        Scenario.logout(d);

        Scenario.joinJailed(d);
        Biting.summonCowNextTo(d);
        bite(d);
        d.sleep(2000);
        d.check("bite from the jail does nothing", bitten(d) == 0, "times bitten: " + bitten(d));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("no bite registered after the impostor left", bitten(d) == 0, "times bitten: " + bitten(d));
        Biting.summonCowNextTo(d);
        bite(d);
        d.sleep(2000);
        d.check("control: bite works after /login", bitten(d) == 1, "times bitten: " + bitten(d));
        d.disconnect();
    }

    private static void bite(ClientDriver d) throws Exception {
        d.sendToServer(new ServerboundStartFeedingPacket(Biting.cowId(d)));
    }

    private static int bitten(ClientDriver d) throws Exception {
        String reply = d.rcon("mt stat " + d.mc().getUser().getName() + " " + BITTEN).trim();
        return Integer.parseInt(reply.substring(reply.lastIndexOf(": ") + 2));
    }
}
