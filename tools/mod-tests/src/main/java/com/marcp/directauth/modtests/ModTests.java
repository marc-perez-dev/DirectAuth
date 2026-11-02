package com.marcp.directauth.modtests;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Arnés de las pruebas con mods reales. En el servidor añade los comandos {@code /mt} que los
 * escenarios usan por RCON para consultar el estado de otras mods; en el cliente
 * ({@link ModTestsClient}) ejecuta el escenario indicado por {@code -Ddirectauth.modtest.scenario}.
 */
public class ModTests implements ModInitializer {
    public static final String MODID = "directauth_modtests";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ServerCommands.register(dispatcher));
    }
}
