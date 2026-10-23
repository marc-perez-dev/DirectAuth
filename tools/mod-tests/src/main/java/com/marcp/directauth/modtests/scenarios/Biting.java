package com.marcp.directauth.modtests.scenarios;

import com.marcp.directauth.modtests.ClientDriver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Ayudas para Vampirism y Werewolves: una vaca quieta junto al jugador a la que morder. */
final class Biting {
    private Biting() {
    }

    static void summonCowNextTo(ClientDriver d) throws Exception {
        String player = d.mc().getUser().getName();
        d.rcon("kill @e[type=minecraft:cow]");
        // Que el cliente olvide la vaca anterior: si no, cowId() podría devolver la muerta
        d.require("old cow gone from the client", d.waitFor(() -> cowId(d) < 0, 10_000), "the client still sees a cow");
        d.rcon("execute at " + player + " run summon minecraft:cow ~1 ~ ~ {NoAI:1b,Invulnerable:0b}");
        d.require("cow visible to the client", d.waitFor(() -> cowId(d) >= 0, 10_000), "no cow in the client world");
    }

    /** Id de red de la vaca en el mundo del cliente (-1 si no la ve). */
    static int cowId(ClientDriver d) {
        if (d.mc().level == null) return -1;
        for (Entity entity : d.mc().level.entitiesForRendering()) {
            if (entity.getType() == EntityType.COW) return entity.getId();
        }
        return -1;
    }

    static float cowHealth(ClientDriver d) throws Exception {
        String reply = d.rcon("data get entity @e[type=minecraft:cow,limit=1] Health");
        return Float.parseFloat(reply.replaceAll(".*: ([0-9.]+)f?\\s*$", "$1").trim());
    }
}
