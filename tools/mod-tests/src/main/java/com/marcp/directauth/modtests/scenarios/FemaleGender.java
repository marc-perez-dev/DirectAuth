package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import com.wildfire.main.Gender;
import com.wildfire.main.WildfireGender;
import com.wildfire.main.entitydata.PlayerConfig;

/**
 * Female Gender: el cliente sincroniza su apariencia con el servidor, que la reenvía a los demás.
 * Desde la jaula no debe cambiar la de la cuenta; tras /login sí.
 */
public class FemaleGender implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNew(d);
        setGender(d, Gender.MALE);
        d.require("appearance synced after /register", Scenario.waitMt(d, "wildfire-gender", "MALE", 5000), Scenario.mt(d, "wildfire-gender"));
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.sleep(1000);
        setGender(d, Gender.FEMALE);
        d.sleep(3000);
        d.check("appearance change from the jail is not applied", !Scenario.mt(d, "wildfire-gender").equals("FEMALE"), Scenario.mt(d, "wildfire-gender"));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        setGender(d, Gender.FEMALE);
        d.check("control: appearance synced after /login", Scenario.waitMt(d, "wildfire-gender", "FEMALE", 5000), Scenario.mt(d, "wildfire-gender"));
        d.disconnect();
    }

    /** Como el menú de la mod: cambia el género y marca la configuración para sincronizarla. */
    private static void setGender(ClientDriver d, Gender gender) throws Exception {
        d.onClientRun(() -> {
            PlayerConfig config = WildfireGender.getOrAddPlayerById(d.mc().player.getUUID());
            config.updateGender(gender);
            config.needsSync = true;
        });
    }
}
