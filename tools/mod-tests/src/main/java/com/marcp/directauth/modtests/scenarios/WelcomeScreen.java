package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import org.lwjgl.glfw.GLFW;

/**
 * Welcome Screen es solo de cliente: abre una pantalla de FancyMenu la primera vez que el jugador
 * entra a un mundo. El servidor no se entera; basta con que se pueda cerrar con Esc para registrarse.
 */
public class WelcomeScreen implements Scenario {
    private static final String SCREEN = "de.keksuccino.fancymenu.";

    @Override
    public void run(ClientDriver d) throws Exception {
        d.connect();
        d.require("DirectAuth asks to register", d.waitChat(WELCOME, 20_000) != null, "chat: " + d.chatSinceMark());
        d.check("welcome screen opens on first join", d.waitFor(() -> Scenario.screenFrom(d, SCREEN), 15_000), d.screenName());
        d.onClientRun(() -> {
            if (d.mc().screen != null) d.mc().screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        });
        d.check("welcome screen closes with Esc", d.waitFor(() -> d.mc().screen == null, 5000), d.screenName());
        d.command("register " + PASSWORD);
        d.check("can register after closing it", d.waitChat(REGISTERED, 20_000) != null, "chat: " + d.chatSinceMark());
        d.disconnect();
    }
}
