package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import io.github.apace100.origins.networking.packet.c2s.ChooseOriginC2SPacket;
import net.minecraft.resources.ResourceLocation;

/**
 * Origins abre el selector de origen (sin poder cerrarlo) en cada entrada mientras no haya origen,
 * y acepta la elección del cliente mientras el jugador está eligiendo.
 */
public class Origins implements Scenario {
    private static final String SCREENS = "io.github.apace100.origins.screen.";
    private static final ChooseOriginC2SPacket CHOOSE_HUMAN = choose("origins:human");
    // El control elige otro origen: así no pasa solo porque el intento desde la jaula se aplicó
    private static final ChooseOriginC2SPacket CHOOSE_MERLING = choose("origins:merling");
    private static final String NO_ORIGIN = "origins:empty";

    @Override
    public void run(ClientDriver d) throws Exception {
        // Cuenta nueva: el selector no debe tapar el chat antes de /register
        Scenario.registerNewChecking(d, "origin picker", SCREENS);
        Scenario.logout(d);

        // Un impostor (sin /login) no puede elegir el origen de la cuenta
        Scenario.joinJailed(d);
        d.sleep(3000);
        d.check("origin picker hidden while jailed (returning player)", !d.screenName().startsWith(SCREENS), d.screenName());
        d.sendToServer(CHOOSE_HUMAN);
        d.sleep(2000);
        d.check("origin chosen from the jail is not applied", origin(d).equals(NO_ORIGIN), origin(d));
        d.disconnect();

        // El dueño entra: sigue sin origen, ve el selector y puede elegir (control positivo)
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("still no origin after the impostor left", origin(d).equals(NO_ORIGIN), origin(d));
        d.check("origin picker opens after /login", d.waitFor(() -> Scenario.screenFrom(d, SCREENS), 15_000), d.screenName());
        d.sendToServer(CHOOSE_MERLING);
        d.sleep(2000);
        d.check("control: origin chosen after /login", origin(d).equals("origins:merling"), origin(d));
        d.disconnect();
    }

    private static ChooseOriginC2SPacket choose(String origin) {
        return new ChooseOriginC2SPacket(ResourceLocation.parse("origins:origin"), ResourceLocation.parse(origin));
    }

    private static String origin(ClientDriver d) throws Exception {
        return Scenario.mt(d, "origin");
    }
}
