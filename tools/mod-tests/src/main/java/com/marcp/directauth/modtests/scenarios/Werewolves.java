package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import de.teamlapen.werewolves.network.ServerboundBiteEventPackage;

/**
 * Werewolves: en forma bestia, ServerboundBiteEventPackage hace daño directamente a la entidad, sin
 * pasar por el ataque vanilla que DirectAuth ya bloquea.
 */
public class Werewolves implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();
        Scenario.registerNew(d);
        d.rcon("vampirism level werewolves:werewolf 1 " + player);
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.require("the account is a werewolf in beast form (set by the server)", Scenario.mt(d, "werewolves-make-beast").equals("true"), "cannot bite");
        Biting.summonCowNextTo(d);
        float before = Biting.cowHealth(d);
        bite(d);
        d.sleep(2000);
        d.check("bite from the jail does no damage", Biting.cowHealth(d) == before, "cow health " + before + " -> " + Biting.cowHealth(d));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        // Tras un mordisco hay unos segundos de espera (si el de la jaula llegó a hacerse)
        d.require("beast form for the control", Scenario.waitMt(d, "werewolves-make-beast", "true", 30_000), "cannot bite");
        Biting.summonCowNextTo(d);
        before = Biting.cowHealth(d);
        bite(d);
        d.sleep(2000);
        d.check("control: bite damages after /login", Biting.cowHealth(d) < before, "cow health " + before + " -> " + Biting.cowHealth(d));
        d.disconnect();
    }

    private static void bite(ClientDriver d) throws Exception {
        d.sendToServer(new ServerboundBiteEventPackage(Biting.cowId(d)));
    }
}
