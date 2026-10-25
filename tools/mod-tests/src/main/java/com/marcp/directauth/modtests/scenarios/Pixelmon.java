package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import com.pixelmonmod.pixelmon.client.ServerStorageDisplay;
import com.pixelmonmod.pixelmon.comm.packetHandlers.ChooseStarterPacket;

/** Pixelmon abre la elección de inicial (sin poder cerrarla) y la reenvía cada 5 s hasta elegir. */
public class Pixelmon implements Scenario {
    private static final String SCREEN = "com.pixelmonmod.pixelmon.client.gui.starter.";

    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNewChecking(d, "starter screen", SCREEN);
        Scenario.logout(d);

        forgetStarterRequest(d);
        Scenario.joinJailed(d);
        d.sleep(6000); // un reenvío completo
        d.check("starter screen hidden while jailed (returning player)", !d.screenName().startsWith(SCREEN), d.screenName());
        chooseStarter(d);
        d.sleep(2000);
        d.check("starter chosen from the jail is not applied", Scenario.mt(d, "pixelmon-starter-picked").equals("false"), Scenario.mt(d, "pixelmon-starter-picked"));
        d.disconnect();

        forgetStarterRequest(d);
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no starter after the impostor left", Scenario.mt(d, "pixelmon-starter-picked").equals("false"), Scenario.mt(d, "pixelmon-starter-picked"));
        d.check("starter screen opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 15_000), d.screenName());
        chooseStarter(d);
        d.sleep(2000);
        d.check("control: starter chosen after /login", Scenario.mt(d, "pixelmon-starter-picked").equals("true"), Scenario.mt(d, "pixelmon-starter-picked"));
        d.disconnect();
    }

    /**
     * El cliente recuerda que debe mostrar la pantalla entre conexiones del mismo proceso (y la
     * reabre en cada tick mientras el equipo esté vacío): se olvida antes de entrar para que solo
     * aparezca si el servidor la vuelve a pedir. Un jugador real que reconecta sin reiniciar el
     * juego sí la vería en la jaula; puede elegir inicial ahí (se aplica al hacer /login).
     */
    private static void forgetStarterRequest(ClientDriver d) throws Exception {
        d.onClientRun(() -> ServerStorageDisplay.showStarterScreen = false);
    }

    private static void chooseStarter(ClientDriver d) throws Exception {
        int options = Integer.parseInt(Scenario.mt(d, "pixelmon-starter-options"));
        d.sendToServer(new ChooseStarterPacket(0, new boolean[options]));
    }
}
