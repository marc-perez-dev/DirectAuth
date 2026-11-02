package com.marcp.directauth.modtests;

import com.marcp.directauth.modtests.checks.CobblemonChecks;
import com.marcp.directauth.modtests.checks.FemaleGenderChecks;
import com.marcp.directauth.modtests.checks.McaChecks;
import com.marcp.directauth.modtests.checks.OriginsChecks;
import com.marcp.directauth.modtests.checks.TensuraChecks;
import com.marcp.directauth.modtests.checks.VoiceChatChecks;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Comandos {@code /mt} del servidor de pruebas, para consultar por RCON el estado de otras mods:
 * {@code /mt <comprobación> <jugador>} responde {@code mt <comprobación> <jugador>: <valor>}.
 * Las comprobaciones de cada mod viven en su propia clase y solo se cargan si la mod está.
 */
final class ServerCommands {
    private ServerCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Map<String, Function<ServerPlayer, String>> checks = new LinkedHashMap<>();
        if (loaded("origins")) OriginsChecks.add(checks);
        if (loaded("mca")) McaChecks.add(checks);
        if (loaded("tensura")) TensuraChecks.add(checks);
        if (loaded("cobblemon")) CobblemonChecks.add(checks);
        if (loaded("voicechat")) VoiceChatChecks.add(checks);
        if (loaded("wildfire_gender")) FemaleGenderChecks.add(checks);

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mt")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("ping").executes(ctx -> reply(ctx.getSource(), "mt pong")));
        for (var check : checks.entrySet()) {
            root.then(Commands.literal(check.getKey())
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> {
                        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                        return reply(ctx.getSource(), "mt " + check.getKey() + " " + player.getGameProfile().getName()
                            + ": " + check.getValue().apply(player));
                    })));
        }
        dispatcher.register(root);
    }

    private static boolean loaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
