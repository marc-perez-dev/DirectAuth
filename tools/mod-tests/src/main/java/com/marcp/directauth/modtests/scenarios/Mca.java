package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import forge.net.mca.MCAClient;
import forge.net.mca.cobalt.network.NetworkHandler;
import forge.net.mca.network.c2s.VillagerEditorSyncRequest;
import net.minecraft.nbt.CompoundTag;

/**
 * MCA Reborn abre Destiny (sin poder cerrarlo) mientras el personaje no está creado. Guardar el
 * personaje desde el editor lo da por completado, y el servidor lo acepta para el propio jugador.
 * Todos sus mensajes van por el SimpleChannel {@code mca:main}; la orden de abrir Destiny es
 * {@code mca:main#36}.
 */
public class Mca implements Scenario {
    private static final String SCREEN = "forge.net.mca.client.gui.DestinyScreen";

    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNewChecking(d, "Destiny screen", SCREEN);
        Scenario.logout(d);

        forgetDestinyRequest(d);
        Scenario.joinJailed(d);
        d.sleep(3000);
        d.check("Destiny screen hidden while jailed (returning player)", !d.screenName().startsWith(SCREEN), d.screenName());
        saveCharacter(d);
        d.sleep(2000);
        d.check("character saved from the jail is not applied", Scenario.mt(d, "mca-destiny-done").equals("false"), Scenario.mt(d, "mca-destiny-done"));
        d.disconnect();

        forgetDestinyRequest(d);
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("Destiny still pending after the impostor left", Scenario.mt(d, "mca-destiny-done").equals("false"), Scenario.mt(d, "mca-destiny-done"));
        d.check("Destiny screen opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 15_000), d.screenName());
        saveCharacter(d);
        d.sleep(2000);
        d.check("control: character saved after /login", Scenario.mt(d, "mca-destiny-done").equals("true"), Scenario.mt(d, "mca-destiny-done"));
        d.disconnect();
    }

    /**
     * El cliente recuerda la petición de abrir Destiny entre conexiones del mismo proceso: se olvida
     * antes de entrar para que la pantalla solo aparezca si el servidor la vuelve a pedir.
     */
    private static void forgetDestinyRequest(ClientDriver d) throws Exception {
        d.onClientRun(() -> MCAClient.getDestinyManager().allowClosing());
    }

    /** Lo que envía el editor de Destiny al guardar el personaje. */
    private static void saveCharacter(ClientDriver d) throws Exception {
        d.onClientRun(() -> NetworkHandler.sendToServer(new VillagerEditorSyncRequest("sync", d.mc().player.getUUID(), new CompoundTag())));
    }
}
