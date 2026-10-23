package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;

/** Sin mods de terceros: comprueba que el arnés conecta, se registra y vuelve a entrar. */
public class Baseline implements Scenario {
    @Override
    public void run(ClientDriver d) throws Exception {
        Scenario.registerNew(d);
        d.check("no screen after /register", d.screenName().equals("none"), d.screenName());
        Scenario.logout(d);
        Scenario.joinJailed(d);
        Scenario.login(d);
        d.check("rcon works", d.rcon("list").contains("players online"), d.rcon("list"));
        d.disconnect();
    }
}
