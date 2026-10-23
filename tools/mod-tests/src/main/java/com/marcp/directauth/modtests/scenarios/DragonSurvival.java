package com.marcp.directauth.modtests.scenarios;

import by.dragonsurvivalteam.dragonsurvival.network.status.SyncAltarCooldown;
import com.marcp.directauth.modtests.ClientDriver;

/**
 * Dragon Survival ofrece el altar de dragón 5 s después de entrar, una vez por sesión, hasta que se
 * usa. El cliente marca el altar como usado con SyncAltarCooldown.
 */
public class DragonSurvival implements Scenario {
    private static final String SCREEN = "by.dragonsurvivalteam.dragonsurvival.client.gui.screens.DragonAltarScreen";

    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNewChecking(d, "dragon altar", SCREEN);
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.sleep(7000);
        d.check("dragon altar hidden while jailed (returning player)", !d.screenName().startsWith(SCREEN), d.screenName());
        d.sendToServer(new SyncAltarCooldown(100));
        d.sleep(2000);
        d.check("altar marked as used from the jail is not applied", Scenario.mt(d, "ds-altar-used").equals("false"), Scenario.mt(d, "ds-altar-used"));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("altar still unused after the impostor left", Scenario.mt(d, "ds-altar-used").equals("false"), Scenario.mt(d, "ds-altar-used"));
        d.check("dragon altar opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 15_000), d.screenName());
        d.sendToServer(new SyncAltarCooldown(100));
        d.sleep(2000);
        d.check("control: altar used after /login", Scenario.mt(d, "ds-altar-used").equals("true"), Scenario.mt(d, "ds-altar-used"));
        d.disconnect();
    }
}
