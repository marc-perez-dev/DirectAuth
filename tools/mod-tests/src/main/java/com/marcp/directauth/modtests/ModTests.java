package com.marcp.directauth.modtests;

import com.marcp.directauth.modtests.checks.PixelmonChecks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Arnés de las pruebas con mods reales. En el cliente ejecuta el escenario indicado por
 * {@code -Ddirectauth.modtest.scenario}; en el servidor añade los comandos {@code /mt} que los
 * escenarios usan por RCON para consultar el estado de otras mods.
 */
@Mod(ModTests.MODID)
public class ModTests {
    public static final String MODID = "directauth_modtests";

    public ModTests() {
        MinecraftForge.EVENT_BUS.addListener(ServerCommands::register);
        if (ModList.get().isLoaded("pixelmon")) PixelmonChecks.workAroundStarterListLeak();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientDriver.install();
        }
    }
}
