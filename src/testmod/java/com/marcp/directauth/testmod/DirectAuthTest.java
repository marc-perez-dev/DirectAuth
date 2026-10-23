package com.marcp.directauth.testmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simula, en el servidor del smoke test, lo que hacen otras mods con un jugador sin autenticar:
 * paquetes propios en las dos direcciones, menús abiertos por el servidor y teletransportes en la
 * primera entrada. Lo que recibe el servidor se consulta por RCON con {@code /datest log <jugador>}.
 */
@Mod(DirectAuthTest.MODID)
public class DirectAuthTest {
    public static final String MODID = "directauth_test";

    /** Lo que el cliente envía a "otra mod" (el smoke test lo inunda como Epic Fight). */
    public record ActionPayload(String value) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(id("action"));
        public static final StreamCodec<ByteBuf, ActionPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(ActionPayload::new, ActionPayload::value);
        @Override public Type<ActionPayload> type() { return TYPE; }
    }

    /** Paquete que el cliente envía una sola vez al conectar (como la clave de Simple Voice Chat). */
    public record HelloPayload(String value) implements CustomPacketPayload {
        public static final Type<HelloPayload> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<ByteBuf, HelloPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(HelloPayload::new, HelloPayload::value);
        @Override public Type<HelloPayload> type() { return TYPE; }
    }

    /** Orden de abrir una pantalla (como el selector de Neo Origins). */
    public record ScreenPayload(String value) implements CustomPacketPayload {
        public static final Type<ScreenPayload> TYPE = new Type<>(id("screen"));
        public static final StreamCodec<ByteBuf, ScreenPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(ScreenPayload::new, ScreenPayload::value);
        @Override public Type<ScreenPayload> type() { return TYPE; }
    }

    /** Datos que una mod sincroniza al entrar y que no se deben aplazar. */
    public record DataPayload(String value) implements CustomPacketPayload {
        public static final Type<DataPayload> TYPE = new Type<>(id("data"));
        public static final StreamCodec<ByteBuf, DataPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.map(DataPayload::new, DataPayload::value);
        @Override public Type<DataPayload> type() { return TYPE; }
    }

    /** Coordenadas a las que "otra mod" manda a los jugadores nuevos (ver onLoginFirst/onLoginLast). */
    public static final double TP_X = 100.5, TP_Y = -60, TP_Z = 100.5;

    // Eventos por jugador (en minúsculas), en orden de llegada
    private static final Map<String, List<String>> LOG = new ConcurrentHashMap<>();

    public DirectAuthTest(IEventBus modBus) {
        modBus.addListener(DirectAuthTest::registerPayloads);
        NeoForge.EVENT_BUS.addListener(DirectAuthTest::registerCommands);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, DirectAuthTest::onLoginFirst);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, DirectAuthTest::onLoginLast);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void log(Player player, String event) {
        LOG.computeIfAbsent(player.getGameProfile().getName().toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Opcionales: así Mineflayer (cliente vanilla) puede conectarse y usarlos
        var registrar = event.registrar("1").optional();
        registrar.playToServer(ActionPayload.TYPE, ActionPayload.CODEC,
            (payload, ctx) -> ctx.enqueueWork(() -> log(ctx.player(), "action:" + payload.value())));
        registrar.playToServer(HelloPayload.TYPE, HelloPayload.CODEC,
            (payload, ctx) -> ctx.enqueueWork(() -> log(ctx.player(), "hello:" + payload.value())));
        registrar.playToClient(ScreenPayload.TYPE, ScreenPayload.CODEC, DirectAuthTest::ignore);
        registrar.playToClient(DataPayload.TYPE, DataPayload.CODEC, DirectAuthTest::ignore);
    }

    private static void ignore(CustomPacketPayload payload, IPayloadContext ctx) {
    }

    /** Menú de cofre que anota los clics en botones (como el selector de raza de Tensura). */
    private static void openMenu(ServerPlayer player, String tag) {
        var result = player.openMenu(new SimpleMenuProvider((id, inventory, p) ->
            new ChestMenu(MenuType.GENERIC_9x3, id, inventory, new SimpleContainer(27), 3) {
                @Override
                public boolean clickMenuButton(Player clicker, int button) {
                    log(clicker, "button:" + tag + ":" + button);
                    return true;
                }
            }, Component.literal("datest " + tag)));
        log(player, "openMenu:" + tag + ":" + (result.isPresent() ? "opened" : "empty"));
    }

    private static boolean neverPlayed(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
    }

    // Simula mods que actúan en la entrada, antes (HIGHEST) o después (LOWEST) que DirectAuth
    private static void onLoginFirst(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String name = player.getGameProfile().getName();
        if (name.startsWith("TpFirst") && neverPlayed(player)) {
            player.teleportTo(TP_X, TP_Y, TP_Z);
        }
        if (name.startsWith("Scr")) {
            PacketDistributor.sendToPlayer(player, new ScreenPayload("login"));
            PacketDistributor.sendToPlayer(player, new DataPayload("login"));
        }
        if (name.startsWith("MenuLogin")) {
            openMenu(player, "login");
        }
    }

    private static void onLoginLast(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getGameProfile().getName().startsWith("TpLast") && neverPlayed(player)) {
            player.teleportTo(TP_X, TP_Y, TP_Z);
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("datest")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("log")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name").toLowerCase();
                        List<String> events = LOG.getOrDefault(name, List.of());
                        reply(ctx.getSource(), "datest log " + name + ": [" + String.join(",", events) + "]");
                        return events.size();
                    })))
            .then(Commands.literal("clear")
                .executes(ctx -> {
                    LOG.clear();
                    reply(ctx.getSource(), "datest cleared");
                    return 1;
                }))
            .then(Commands.literal("screen")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            PacketDistributor.sendToPlayer(player, new ScreenPayload(StringArgumentType.getString(ctx, "value")));
                            reply(ctx.getSource(), "datest screen sent");
                            return 1;
                        }))))
            .then(Commands.literal("data")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            PacketDistributor.sendToPlayer(player, new DataPayload(StringArgumentType.getString(ctx, "value")));
                            reply(ctx.getSource(), "datest data sent");
                            return 1;
                        }))))
            .then(Commands.literal("menu")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> {
                            openMenu(EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "tag"));
                            reply(ctx.getSource(), "datest menu requested");
                            return 1;
                        })))));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
