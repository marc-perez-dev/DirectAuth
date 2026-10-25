package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.network.client.CPChangePlayerMode;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;

/**
 * Epic Fight: el cliente envía su movimiento en cada tick y, al conectar, una vez su registro de
 * animaciones (si no coincide con el del servidor, este lo expulsa). Nada de eso debe romper la
 * conexión de un jugador en la jaula ni después de /login. El cambio de modo es una acción más.
 * Todo va por el SimpleChannel {@code epicfight:network_manager}: movimiento {@code #4}, registro de
 * animaciones {@code #8} y cambio de modo {@code #3}.
 */
public class EpicFight implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNew(d);
        Scenario.logout(d);

        Scenario.joinJailed(d);
        String initial = Scenario.mt(d, "epicfight-mode");
        d.sleep(15_000); // ~300 paquetes de movimiento
        d.check("still connected after 15 s in the jail", d.connected(), "chat: " + d.chatSinceMark());
        changeMode(d, opposite(initial));
        d.sleep(2000);
        d.check("mode change from the jail is not applied", Scenario.mt(d, "epicfight-mode").equals(initial), Scenario.mt(d, "epicfight-mode"));
        d.disconnect();

        Scenario.joinJailed(d);
        d.check("mode unchanged after the impostor left", Scenario.mt(d, "epicfight-mode").equals(initial), Scenario.mt(d, "epicfight-mode"));
        Scenario.login(d);
        d.sleep(10_000);
        d.check("still connected 10 s after /login (animation registry validated)", d.connected(), "chat: " + d.chatSinceMark());
        changeMode(d, opposite(initial));
        d.sleep(2000);
        d.check("control: mode change after /login", Scenario.mt(d, "epicfight-mode").equals(opposite(initial)), Scenario.mt(d, "epicfight-mode"));
        d.disconnect();
    }

    private static String opposite(String mode) {
        return mode.equals("epicfight") ? "vanilla" : "epicfight";
    }

    private static void changeMode(ClientDriver d, String mode) throws Exception {
        PlayerPatch.PlayerMode target = mode.equals("epicfight") ? PlayerPatch.PlayerMode.EPICFIGHT : PlayerPatch.PlayerMode.VANILLA;
        d.onClientRun(() -> EpicFightNetworkManager.sendToServer(new CPChangePlayerMode(target)));
    }
}
