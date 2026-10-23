package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/**
 * Tensura abre la elección de raza como menú de contenedor del servidor (sin poder cerrarlo), y
 * la elección viaja como clic de botón de contenedor, un paquete vanilla.
 */
public class Tensura implements Scenario {
    private static final String SCREEN = "io.github.manasmods.tensura.client.screen.ReincarnationScreen";
    /** Botón "confirmar" del menú de reencarnación. */
    private static final int SUBMIT = -1;

    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNewChecking(d, "race selection", SCREEN);
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.sleep(3000);
        d.check("race selection hidden while jailed (returning player)", !d.screenName().startsWith(SCREEN), d.screenName());
        clickSubmit(d);
        d.sleep(2000);
        d.check("race chosen from the jail is not applied", Scenario.mt(d, "tensura-has-race").equals("false"), Scenario.mt(d, "tensura-has-race"));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no race after the impostor left", Scenario.mt(d, "tensura-has-race").equals("false"), Scenario.mt(d, "tensura-has-race"));
        d.check("race selection opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 15_000), d.screenName());
        clickSubmit(d);
        d.sleep(2000);
        d.check("control: race chosen after /login", Scenario.mt(d, "tensura-has-race").equals("true"), Scenario.mt(d, "tensura-has-race"));
        d.disconnect();
    }

    /**
     * Como el botón de confirmar de la pantalla: un clic de botón en el menú abierto. Un impostor no
     * necesita ver el menú: los ids de contenedor son correlativos, así que se prueban los primeros
     * además del que tenga abierto el cliente.
     */
    private static void clickSubmit(ClientDriver d) throws Exception {
        d.onClientRun(() -> {
            int open = d.mc().player.containerMenu.containerId;
            d.mc().gameMode.handleInventoryButtonClick(open, SUBMIT);
            for (int id = 1; id <= 10; id++) {
                if (id != open) d.mc().gameMode.handleInventoryButtonClick(id, SUBMIT);
            }
        });
    }
}
