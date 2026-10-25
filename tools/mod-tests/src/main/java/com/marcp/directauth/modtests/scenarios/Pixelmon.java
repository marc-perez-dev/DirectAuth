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

        // Quien reconecta sin reiniciar el juego y sin inicial: el cliente le muestra la pantalla en
        // la jaula por su cuenta, elige ahí y luego hace /login
        d.rcon("pokerestart " + d.mc().getUser().getName() + " --confirm");
        d.require("starter reset by an admin", Scenario.waitMt(d, "pixelmon-starter-picked", "false", 5000), Scenario.mt(d, "pixelmon-starter-picked"));
        Scenario.logout(d);
        d.onClientRun(() -> ServerStorageDisplay.showStarterScreen = true);
        Scenario.joinJailed(d);
        d.require("the client shows its remembered starter screen in the jail", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 10_000), d.screenName());
        chooseStarterLikeTheScreen(d);
        Scenario.login(d);
        d.check("starter chosen in the jail by the owner is applied after /login",
            Scenario.waitMt(d, "pixelmon-starter-picked", "true", 5000), Scenario.mt(d, "pixelmon-starter-picked"));
        // La pantalla aplazada llega igualmente y la reabre (DirectAuth no puede saber que ya no hace
        // falta). Una elección más la cierra y el servidor la ignora: el inicial es el de la jaula.
        if (d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 6000)) {
            chooseStarterLikeTheScreen(d);
        }
        d.check("a starter screen reopened after /login closes with one more choice", !d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 3000), d.screenName());
        d.check("the starter chosen in the jail is kept (no second one)", Scenario.waitMt(d, "pixelmon-party-size", "1", 3000),
            "party size " + Scenario.mt(d, "pixelmon-party-size"));
        d.disconnect();
    }

    /** Lo que hace el botón de la pantalla: envía la elección, olvida la pantalla y la cierra. */
    private static void chooseStarterLikeTheScreen(ClientDriver d) throws Exception {
        chooseStarter(d);
        d.onClientRun(() -> {
            ServerStorageDisplay.showStarterScreen = false;
            d.mc().player.closeContainer();
            d.mc().setScreen(null);
        });
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
