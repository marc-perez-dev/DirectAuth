package com.marcp.directauth.testmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.netty.buffer.ByteBuf;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simula, en el servidor del smoke test, lo que hacen otras mods de Fabric con un jugador sin
 * autenticar: paquetes propios en las dos direcciones (Fabric API y como Architectury), menús
 * abiertos por el servidor (vanilla y ExtendedScreenHandlerFactory de Fabric API), una mod que lee
 * el chat y teletransportes en la primera entrada. Lo que recibe el servidor se consulta por RCON
 * con {@code /datest log <jugador>}.
 */
public class DirectAuthTest implements ModInitializer {
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

    /** Orden de abrir una pantalla (como el selector de Origins). */
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

    /**
     * Como Architectury 13 en Fabric 1.21.1 (NetworkAggregator.BufCustomPacketPayload): un tipo de
     * payload por id de mensaje, registrado con PayloadTypeRegistry, cuyo contenido es el mensaje ya
     * codificado como array de bytes (FTB Quests, FTB Teams, KubeJS...).
     */
    public record BufPayload(Type<BufPayload> type, byte[] payload) implements CustomPacketPayload {
        static StreamCodec<ByteBuf, BufPayload> codec(Type<BufPayload> type) {
            return ByteBufCodecs.BYTE_ARRAY.map(bytes -> new BufPayload(type, bytes), BufPayload::payload);
        }
    }

    /** Mensajes "de Architectury" que el smoke test retiene en la jaula (directauth_test:arch_*). */
    private static final List<String> ARCH_MESSAGES = List.of("arch_claim", "arch_team");
    /** Tipos de relleno para llenar el límite de lo retenido por conexión (64 tipos, 1 MiB). */
    public static final int JUNK_TYPES = 70;

    /** Menú con datos extra de Fabric API (ExtendedScreenHandlerFactory): el dato es la etiqueta. */
    public static final ExtendedScreenHandlerType<TaggedMenu, String> EXTENDED_MENU =
        new ExtendedScreenHandlerType<>((syncId, inventory, tag) -> new TaggedMenu(syncId, tag), ByteBufCodecs.STRING_UTF8);

    /** Coordenadas a las que "otra mod" manda a los jugadores nuevos (TpFirst…/TpLast…). */
    public static final double TP_X = 100.5, TP_Y = -60, TP_Z = 100.5;

    // Eventos por jugador (en minúsculas), en orden de llegada
    private static final Map<String, List<String>> LOG = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        registerPayloads();
        Registry.register(BuiltInRegistries.MENU, id("extended"), EXTENDED_MENU);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        // Como una mod que actúa al entrar: JOIN se dispara a mitad de PlayerList.placeNewPlayer,
        // antes del manejador de entrada de DirectAuth (al final de placeNewPlayer)
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void log(Player player, String event) {
        LOG.computeIfAbsent(player.getGameProfile().getName().toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ScreenPayload.TYPE, ScreenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DataPayload.TYPE, DataPayload.CODEC);
        // Los receptores de Fabric API corren en el hilo del servidor
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, ctx) -> log(ctx.player(), "action:" + payload.value()));
        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, ctx) -> log(ctx.player(), "hello:" + payload.value()));
        for (String message : ARCH_MESSAGES) {
            registerBuf(message, (player, bytes) -> log(player, "arch:" + message + ":" + readString(bytes)));
        }
        for (int i = 0; i < JUNK_TYPES; i++) {
            String name = "junk" + i;
            registerBuf(name, (player, bytes) -> log(player, name));
        }
    }

    private static void registerBuf(String path, java.util.function.BiConsumer<ServerPlayer, byte[]> receiver) {
        CustomPacketPayload.Type<BufPayload> type = new CustomPacketPayload.Type<>(id(path));
        PayloadTypeRegistry.playC2S().register(type, BufPayload.codec(type));
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, ctx) -> receiver.accept(ctx.player(), payload.payload()));
    }

    /** El mensaje dentro del array de bytes: un String como ByteBufCodecs.STRING_UTF8 (longitud VarInt). */
    private static String readString(byte[] bytes) {
        int length = 0, shift = 0, i = 0;
        while (true) {
            if (i >= bytes.length || shift > 28) throw new IllegalArgumentException("bad VarInt length");
            byte b = bytes[i++];
            length |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (length < 0 || length > bytes.length - i) throw new IllegalArgumentException("string length " + length + " out of bounds");
        return new String(bytes, i, length, StandardCharsets.UTF_8);
    }

    /**
     * Envía un payload solo si el cliente declaró el canal (minecraft:register), como hacen las
     * mods con ServerPlayNetworking.canSend.
     */
    private static boolean sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (!ServerPlayNetworking.canSend(player, payload.type())) return false;
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    /** Menú de cofre que anota los clics en botones (como el selector de raza de Tensura). */
    private static void openMenu(ServerPlayer player, String tag) {
        var result = player.openMenu(new SimpleMenuProvider((id, inventory, p) -> {
            log(p, "createMenu:" + tag);
            return new ChestMenu(MenuType.GENERIC_9x3, id, inventory, new SimpleContainer(27), 3) {
                @Override
                public boolean clickMenuButton(Player clicker, int button) {
                    log(clicker, "button:" + tag + ":" + button);
                    return true;
                }
            };
        }, Component.literal("datest " + tag)));
        log(player, "openMenu:" + tag + ":" + (result.isPresent() ? "opened" : "empty"));
    }

    /** Menú con datos extra (Fabric API): el cliente lo abre con el payload fabric-screen-handler-api-v1:open_screen. */
    private static void openExtendedMenu(ServerPlayer player, String tag) {
        var result = player.openMenu(new ExtendedScreenHandlerFactory<String>() {
            @Override
            public String getScreenOpeningData(ServerPlayer p) {
                return tag;
            }

            @Override
            public Component getDisplayName() {
                return Component.literal("datest " + tag);
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player p) {
                log(p, "createMenu:" + tag);
                return new TaggedMenu(syncId, tag);
            }
        });
        log(player, "openMenu:" + tag + ":" + (result.isPresent() ? "opened" : "empty"));
    }

    /** Menú sin ranuras del tipo extendido; anota los clics en botones. */
    public static class TaggedMenu extends AbstractContainerMenu {
        private final String tag;

        public TaggedMenu(int syncId, String tag) {
            super(EXTENDED_MENU, syncId);
            this.tag = tag;
        }

        @Override
        public boolean clickMenuButton(Player clicker, int button) {
            log(clicker, "button:" + tag + ":" + button);
            return true;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    public static boolean neverPlayed(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
    }

    /** Teletransporte de "otra mod" a los jugadores nuevos. */
    public static void teleportNewPlayer(ServerPlayer player) {
        player.teleportTo(TP_X, TP_Y, TP_Z);
    }

    // Simula mods que actúan al entrar, antes que DirectAuth (ServerPlayConnectionEvents.JOIN).
    // Los que actúan después están en MixinPlayerListLast.
    private static void onJoin(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        if (name.startsWith("TpFirst") && neverPlayed(player)) {
            teleportNewPlayer(player);
        }
        if (name.startsWith("Scr")) {
            sendIfSupported(player, new ScreenPayload("login"));
            sendIfSupported(player, new DataPayload("login"));
        }
        if (name.startsWith("MenuLogin")) {
            openMenu(player, "login");
        }
        if (name.startsWith("XMenuLogin")) {
            openExtendedMenu(player, "login");
        }
    }

    private static void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("datest")
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
                            boolean sent = sendIfSupported(player, new ScreenPayload(StringArgumentType.getString(ctx, "value")));
                            reply(ctx.getSource(), sent ? "datest screen sent" : "datest screen: channel not declared by the client");
                            return sent ? 1 : 0;
                        }))))
            .then(Commands.literal("data")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            boolean sent = sendIfSupported(player, new DataPayload(StringArgumentType.getString(ctx, "value")));
                            reply(ctx.getSource(), sent ? "datest data sent" : "datest data: channel not declared by the client");
                            return sent ? 1 : 0;
                        }))))
            .then(Commands.literal("menu")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> {
                            openMenu(EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "tag"));
                            reply(ctx.getSource(), "datest menu requested");
                            return 1;
                        }))))
            .then(Commands.literal("xmenu")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> {
                            openExtendedMenu(EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "tag"));
                            reply(ctx.getSource(), "datest xmenu requested");
                            return 1;
                        })))));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
