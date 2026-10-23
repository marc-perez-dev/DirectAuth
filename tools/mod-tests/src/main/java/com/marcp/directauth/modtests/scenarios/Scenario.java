package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/**
 * Un escenario por mod. Se carga por nombre (run.js --scenario) para que las clases de una mod
 * solo se resuelvan cuando esa mod está instalada.
 */
public interface Scenario {
    String PASSWORD = "secret1";

    void run(ClientDriver d) throws Exception;

    // Mensajes de DirectAuth (idioma por defecto, en)
    String WELCOME = "Use /register";
    String LOGIN_REQUEST = "Use /login";
    String REGISTERED = "Account registered";
    String AUTHENTICATED = "Authenticated successfully";

    /** Primera entrada de una cuenta nueva: espera el aviso de DirectAuth y se registra. */
    static void registerNew(ClientDriver d) throws Exception {
        d.connect();
        d.require("DirectAuth asks to register", d.waitChat(WELCOME, 20_000) != null, "chat: " + d.chatSinceMark());
        d.sleep(1200); // registrationDelay
        d.command("register " + PASSWORD);
        d.require("registered", d.waitChat(REGISTERED, 20_000) != null, "chat: " + d.chatSinceMark());
    }

    /**
     * Primera entrada de una cuenta nueva en una mod que abre una pantalla al entrar: la pantalla
     * no debe aparecer mientras el jugador está en la jaula, y sí después de /register.
     */
    static void registerNewChecking(ClientDriver d, String what, String screenPrefix) throws Exception {
        d.connect();
        d.require("DirectAuth asks to register", d.waitChat(WELCOME, 20_000) != null, "chat: " + d.chatSinceMark());
        // Las mods abren su pantalla al entrar o unos segundos después (Dragon Survival: 5 s)
        d.sleep(7000);
        d.check(what + " hidden while jailed (new player)", !d.screenName().startsWith(screenPrefix), d.screenName());
        d.command("register " + PASSWORD);
        d.require("registered", d.waitChat(REGISTERED, 20_000) != null, "chat: " + d.chatSinceMark());
        d.check(what + " opens after /register", d.waitFor(() -> {
            var screen = d.mc().screen;
            return screen != null && screen.getClass().getName().startsWith(screenPrefix);
        }, 15_000), d.screenName());
    }

    /** Entrada de una cuenta existente, sin autenticarse todavía. */
    static void joinJailed(ClientDriver d) throws Exception {
        d.connect();
        d.require("DirectAuth asks to log in", d.waitChat(LOGIN_REQUEST, 20_000) != null, "chat: " + d.chatSinceMark());
    }

    /** /logout: DirectAuth expulsa y anula la sesión de gracia, así que la siguiente entrada pide /login. */
    static void logout(ClientDriver d) throws Exception {
        d.command("logout");
        d.require("logged out", d.waitFor(() -> d.mc().level == null, 20_000), "chat: " + d.chatSinceMark());
    }

    /** Valor de una comprobación {@code /mt <check> <jugador>} del servidor de pruebas. */
    static String mt(ClientDriver d, String check) throws Exception {
        String reply = d.rcon("mt " + check + " " + d.mc().getUser().getName()).trim();
        return reply.substring(reply.lastIndexOf(": ") + 2);
    }

    /** Espera a que {@code /mt <check>} valga {@code expected}; devuelve si lo ha hecho. */
    static boolean waitMt(ClientDriver d, String check, String expected, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (mt(d, check).equals(expected)) return true;
            d.sleep(250);
        }
        return false;
    }

    static boolean screenFrom(ClientDriver d, String prefix) {
        var screen = d.mc().screen;
        return screen != null && screen.getClass().getName().startsWith(prefix);
    }

    static void login(ClientDriver d) throws Exception {
        d.command("login " + PASSWORD);
        d.require("logged in", d.waitChat(AUTHENTICATED, 20_000) != null, "chat: " + d.chatSinceMark());
    }
}
