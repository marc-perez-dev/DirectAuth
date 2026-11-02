package com.marcp.directauth.modtests;

import net.fabricmc.api.ClientModInitializer;

/** Parte de cliente del arnés: conduce el escenario (ver {@link ClientDriver}). */
public class ModTestsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientDriver.install();
    }
}
