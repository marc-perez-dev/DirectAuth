package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import com.natamus.collective_common_forge.implementations.networking.api.Dispatcher;
import com.serilum.starterkit_common_forge.networking.packets.ToServerSendKitChoicePacket;

/**
 * Starter Kit con varios kits para elegir: al aparecer por primera vez, el servidor pregunta al
 * cliente si tiene la mod y espera la respuesta 5 s; si llega, le abre la pantalla de elegir kit,
 * y si no, le da a elegir por comandos de chat.
 */
public class StarterKit implements Scenario {
    private static final String SCREEN = "com.serilum.starterkit_common_forge.inventory.";

    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNewChecking(d, "kit choice screen", SCREEN);
        // El dueño se va sin elegir kit
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.sleep(2000);
        chooseKit(d);
        d.sleep(2000);
        d.check("kit chosen from the jail is not given", bows(d) == 0, "bows in the inventory: " + bows(d));
        d.disconnect();

        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("no kit after the impostor left", bows(d) == 0, "bows in the inventory: " + bows(d));
        chooseKit(d);
        d.sleep(2000);
        d.check("control: kit chosen after /login", bows(d) == 1, "bows in the inventory: " + bows(d));
        d.disconnect();
    }

    /** Lo que envía la pantalla al elegir el kit Archer (el único con arco). */
    private static void chooseKit(ClientDriver d) throws Exception {
        d.onClientRun(() -> Dispatcher.sendToServer(new ToServerSendKitChoicePacket("Archer")));
    }

    /** Arcos en el inventario; cualquier otra respuesta (jugador no conectado...) es un error, no un 0. */
    private static int bows(ClientDriver d) throws Exception {
        String reply = d.rcon("clear " + d.mc().getUser().getName() + " minecraft:bow 0");
        if (reply.matches("(?s).*Found (\\d+).*")) return Integer.parseInt(reply.replaceAll("(?s).*Found (\\d+).*", "$1"));
        if (reply.contains("No items were found")) return 0;
        throw new IllegalStateException("unexpected reply to clear: " + reply);
    }
}
