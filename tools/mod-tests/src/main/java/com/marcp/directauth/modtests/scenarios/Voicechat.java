package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import de.maxhenkel.voicechat.voice.client.ClientManager;

/**
 * Simple Voice Chat: el cliente pide la clave de voz una sola vez al entrar. En la jaula no debe
 * haber chat de voz (el de texto tampoco), pero tras /login sí, sin tener que reconectar.
 */
public class Voicechat implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNew(d);
        d.check("voice chat connects for a new player after /register", d.waitFor(Voicechat::voiceConnected, 20_000), "not connected");
        Scenario.logout(d);

        Scenario.joinJailed(d);
        d.sleep(8000);
        boolean connected = d.onClient(Voicechat::voiceConnected);
        String secret = Scenario.mt(d, "voicechat-has-secret");
        d.check("no voice chat while jailed", !connected && secret.equals("false"), "client connected: " + connected + ", server secret: " + secret);
        Scenario.login(d);
        d.check("voice chat connects after /login without reconnecting", d.waitFor(Voicechat::voiceConnected, 20_000), "not connected");
        d.disconnect();
    }

    /** Solo en el hilo del cliente (waitFor y onClient ya lo hacen). */
    private static boolean voiceConnected() {
        var client = ClientManager.getClient();
        return client != null && client.getConnection() != null && client.getConnection().isInitialized();
    }
}
