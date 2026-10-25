package com.marcp.directauth.modtests.checks;

import com.pixelmonmod.pixelmon.TickHandler;
import com.pixelmonmod.pixelmon.api.storage.StorageProxy;
import com.pixelmonmod.pixelmon.config.starter.StarterList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.function.Function;

/** Pixelmon: ¿ha elegido el jugador inicial? y cuántas opciones espera el paquete de elegirlo. */
public final class PixelmonChecks {
    private PixelmonChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("pixelmon-starter-picked", player -> String.valueOf(StorageProxy.getPartyNow(player).starterPicked));
        checks.put("pixelmon-starter-options", player -> String.valueOf(StarterList.getOptions().size()));
        checks.put("pixelmon-party-size", player -> String.valueOf(StorageProxy.getPartyNow(player).countAll()));
    }

    /**
     * Fallo de Pixelmon 9.2.3, no de DirectAuth: un jugador que se va sin elegir inicial se queda en
     * la lista a la que reenvía la pantalla cada 5 s, y el reenvío tumba el servidor (NPE en
     * PacketDistributor) si no ha vuelto a entrar. Al irse se le saca de la lista, como debería hacer
     * Pixelmon; al volver, Pixelmon lo apunta otra vez si sigue sin inicial.
     */
    public static void workAroundStarterListLeak() {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) TickHandler.deregisterStarterList(player);
        });
    }
}
