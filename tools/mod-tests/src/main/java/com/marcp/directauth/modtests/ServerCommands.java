package com.marcp.directauth.modtests;

import com.marcp.directauth.modtests.checks.CobblemonChecks;
import com.marcp.directauth.modtests.checks.DragonSurvivalChecks;
import com.marcp.directauth.modtests.checks.EpicFightChecks;
import com.marcp.directauth.modtests.checks.FemaleGenderChecks;
import com.marcp.directauth.modtests.checks.McaChecks;
import com.marcp.directauth.modtests.checks.PixelmonChecks;
import com.marcp.directauth.modtests.checks.TensuraChecks;
import com.marcp.directauth.modtests.checks.VoiceChatChecks;
import com.marcp.directauth.modtests.checks.WerewolvesChecks;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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

    static void register(RegisterCommandsEvent event) {
        Map<String, Function<ServerPlayer, String>> checks = new LinkedHashMap<>();
        if (ModList.get().isLoaded("mca")) McaChecks.add(checks);
        if (ModList.get().isLoaded("pixelmon")) PixelmonChecks.add(checks);
        if (ModList.get().isLoaded("tensura")) TensuraChecks.add(checks);
        if (ModList.get().isLoaded("cobblemon")) CobblemonChecks.add(checks);
        if (ModList.get().isLoaded("dragonsurvival")) DragonSurvivalChecks.add(checks);
        if (ModList.get().isLoaded("werewolves")) WerewolvesChecks.add(checks);
        if (ModList.get().isLoaded("epicfight")) EpicFightChecks.add(checks);
        if (ModList.get().isLoaded("voicechat")) VoiceChatChecks.add(checks);
        if (ModList.get().isLoaded("wildfire_gender")) FemaleGenderChecks.add(checks);

        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mt")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("ping").executes(ctx -> reply(ctx.getSource(), "mt pong")))
            // Estadística personalizada (p. ej. vampirism:amount_bitten)
            .then(Commands.literal("stat")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("stat", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            // Stats.CUSTOM necesita la instancia registrada, no un ResourceLocation igual
                            ResourceLocation stat = BuiltInRegistries.CUSTOM_STAT.get(ResourceLocation.parse(StringArgumentType.getString(ctx, "stat")));
                            String value = stat == null ? "unknown stat" : String.valueOf(player.getStats().getValue(Stats.CUSTOM.get(stat)));
                            return reply(ctx.getSource(), "mt stat " + player.getGameProfile().getName() + ": " + value);
                        }))));
        for (var check : checks.entrySet()) {
            root.then(Commands.literal(check.getKey())
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> {
                        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                        return reply(ctx.getSource(), "mt " + check.getKey() + " " + player.getGameProfile().getName()
                            + ": " + check.getValue().apply(player));
                    })));
        }
        event.getDispatcher().register(root);
    }

    private static int reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
