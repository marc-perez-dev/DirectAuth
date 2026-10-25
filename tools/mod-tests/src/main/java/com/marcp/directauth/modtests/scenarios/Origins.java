package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import io.github.edwinmindcraft.origins.common.OriginsCommon;
import io.github.edwinmindcraft.origins.common.network.C2SChooseOrigin;
import net.minecraft.resources.ResourceLocation;

/**
 * Origins (Forge) abre el selector de origen (sin poder cerrarlo) en cada entrada mientras no haya
 * origen, y acepta la elección del cliente. Todos sus mensajes van por el SimpleChannel
 * {@code origins:network}; la orden de abrir el selector es {@code origins:network#1}.
 */
public class Origins implements Scenario {
    private static final String SCREENS = "io.github.apace100.origins.screen.";
    private static final ResourceLocation LAYER = new ResourceLocation("origins", "origin");

    @Override
    public void run(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();

        // Cuenta nueva: el selector no debe tapar el chat antes de /register
        Scenario.registerNewChecking(d, "origin picker", SCREENS);
        Scenario.logout(d);

        // Un impostor (sin /login) no puede elegir el origen de la cuenta
        Scenario.joinJailed(d);
        d.sleep(3000);
        d.check("origin picker hidden while jailed (returning player)", !d.screenName().startsWith(SCREENS), d.screenName());
        choose(d, "human");
        d.sleep(2000);
        d.check("origin chosen from the jail is not applied", origin(d, player).contains("(origins:empty)"), origin(d, player));
        d.disconnect();

        // El dueño entra: sigue sin origen, ve el selector y puede elegir (control positivo). El
        // control elige otro origen: así no pasa solo porque el intento desde la jaula se aplicó.
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no origin after the impostor left", origin(d, player).contains("(origins:empty)"), origin(d, player));
        d.check("origin picker opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREENS), 15_000), d.screenName());
        choose(d, "merling");
        d.sleep(2000);
        d.check("control: origin chosen after /login", origin(d, player).contains("(origins:merling)"), origin(d, player));
        d.disconnect();
    }

    /** Lo que envía el selector al elegir un origen de la capa principal. */
    private static void choose(ClientDriver d, String origin) throws Exception {
        d.onClientRun(() -> OriginsCommon.CHANNEL.sendToServer(new C2SChooseOrigin(LAYER, new ResourceLocation("origins", origin))));
    }

    /** "ModTester has the following Origin: Missing (origins:empty)" */
    private static String origin(ClientDriver d, String player) throws Exception {
        return d.rcon("origin get " + player + " origins:origin");
    }
}
