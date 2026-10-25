package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import com.wildfire.main.GenderPlayer;
import com.wildfire.main.GenderPlayer.Gender;
import com.wildfire.main.WildfireGender;

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

        // El cliente aún recuerda FEMALE y lo vuelve a sincronizar al entrar: se retiene hasta /login.
        // El control usa otro valor para no confundirse con eso.
        Scenario.joinJailed(d);
        d.sleep(3000);
        d.check("still the owner's appearance after the impostor left", Scenario.mt(d, "wildfire-gender").equals("MALE"), Scenario.mt(d, "wildfire-gender"));
        Scenario.login(d);
        setGender(d, Gender.OTHER);
        d.check("control: appearance synced after /login", Scenario.waitMt(d, "wildfire-gender", "OTHER", 5000), Scenario.mt(d, "wildfire-gender"));
        d.disconnect();
    }

    /** Como el menú de la mod: cambia el género y marca la configuración para sincronizarla. */
    private static void setGender(ClientDriver d, Gender gender) throws Exception {
        d.onClientRun(() -> {
            GenderPlayer plr = WildfireGender.getOrAddPlayerById(d.mc().player.getUUID());
            plr.updateGender(gender);
            plr.needsSync = true;
        });
    }
}
