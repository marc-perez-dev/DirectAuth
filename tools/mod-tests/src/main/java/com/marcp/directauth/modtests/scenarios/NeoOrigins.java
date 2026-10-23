package com.marcp.directauth.modtests.scenarios;

import com.cyberday1.neoorigins.network.payload.ChooseOriginPayload;
import com.marcp.directauth.modtests.ClientDriver;
import net.minecraft.resources.ResourceLocation;

/**
 * Neo Origins abre el selector de origen (sin poder cerrarlo) en cada entrada mientras no haya
 * origen, y acepta la elección del cliente.
 */
public class NeoOrigins implements Scenario {
    private static final String SCREENS = "com.cyberday1.neoorigins.";
    private static final ChooseOriginPayload CHOOSE_HUMAN = choose("neoorigins:human");
    // El control elige otro origen: así no pasa solo porque el intento desde la jaula se aplicó
    private static final ChooseOriginPayload CHOOSE_MERLING = choose("neoorigins:merling");

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
        d.sendToServer(CHOOSE_HUMAN);
        d.sleep(2000);
        d.check("origin chosen from the jail is not applied", origin(d, player).contains("has no origin"), origin(d, player));
        d.disconnect();

        // El dueño entra: sigue sin origen, ve el selector y puede elegir (control positivo)
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no origin after the impostor left", origin(d, player).contains("has no origin"), origin(d, player));
        d.check("origin picker opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREENS), 15_000), d.screenName());
        d.sendToServer(CHOOSE_MERLING);
        d.sleep(2000);
        d.check("control: origin chosen after /login", origin(d, player).contains("neoorigins:merling"), origin(d, player));
        d.disconnect();
    }

    private static ChooseOriginPayload choose(String origin) {
        return new ChooseOriginPayload(ResourceLocation.parse("neoorigins:origin"), ResourceLocation.parse(origin));
    }

    private static String origin(ClientDriver d, String player) throws Exception {
        return d.rcon("origin get " + player + " neoorigins:origin");
    }

}
