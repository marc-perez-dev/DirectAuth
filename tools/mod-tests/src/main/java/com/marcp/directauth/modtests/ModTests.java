package com.marcp.directauth.modtests;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Arnés de las pruebas con mods reales. En el cliente ejecuta el escenario indicado por
 * {@code -Ddirectauth.modtest.scenario}; en el servidor añade los comandos {@code /mt} que los
 * escenarios usan por RCON para consultar el estado de otras mods.
 */
@Mod(ModTests.MODID)
public class ModTests {
    public static final String MODID = "directauth_modtests";

    public ModTests() {
        NeoForge.EVENT_BUS.addListener(ServerCommands::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDriver.install();
        }
    }
}
