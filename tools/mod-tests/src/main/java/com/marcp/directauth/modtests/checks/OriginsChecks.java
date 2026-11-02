package com.marcp.directauth.modtests.checks;

import io.github.apace100.origins.component.OriginComponent;
import io.github.apace100.origins.origin.OriginLayerManager;
import io.github.apace100.origins.registry.ModComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.function.Function;

/** Origins: origen del jugador en la capa principal ({@code origins:empty} si no tiene). */
public final class OriginsChecks {
    private OriginsChecks() {
    }

    public static void add(Map<String, Function<ServerPlayer, String>> checks) {
        checks.put("origin", player -> {
            OriginComponent component = ModComponents.ORIGIN.get(player);
            return component.getOrigin(OriginLayerManager.get(ResourceLocation.parse("origins:origin"))).getId().toString();
        });
    }
}
