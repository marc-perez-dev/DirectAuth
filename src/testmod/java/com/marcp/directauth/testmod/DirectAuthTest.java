package com.marcp.directauth.testmod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Simula, en el servidor del smoke test, lo que hacen otras mods con un jugador sin autenticar:
 * paquetes propios en las dos direcciones, menús abiertos por el servidor y teletransportes en la
 * primera entrada. Lo que recibe el servidor se consulta por RCON con {@code /datest log <jugador>}.
 * <p>
 * Como la mayoría de mods de Forge, envía todos sus mensajes por un SimpleChannel, con el índice
 * del mensaje en el primer byte: DirectAuth los distingue como {@code directauth_test:main#índice}.
 */
@Mod(DirectAuthTest.MODID)
public class DirectAuthTest {
    public static final String MODID = "directauth_test";
    private static final String VERSION = "1";

    // Opcional en ambos lados: así Mineflayer (cliente vanilla) puede conectarse y usarlo
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(MODID, "main"),
        () -> VERSION, NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    // Como el canal único de Architectury (FTB Quests, FTB Teams...): cada mensaje empieza por su id
    private static final EventNetworkChannel ARCHITECTURY = NetworkRegistry.newEventChannel(new ResourceLocation("architectury", "network"),
        () -> VERSION, NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    /** Índice 0: lo que el cliente envía a "otra mod" (el smoke test lo inunda como Epic Fight). */
    public record ActionPayload(String value) {}

    /** Índice 1: paquete que el cliente envía una sola vez al conectar (como la clave de Simple Voice Chat). */
    public record HelloPayload(String value) {}

    /** Índice 2: orden de abrir una pantalla (como el selector de orígenes). */
    public record ScreenPayload(String value) {}

    /** Índice 3: datos que una mod sincroniza al entrar y que no se deben aplazar. */
    public record DataPayload(String value) {}

    /** Coordenadas a las que "otra mod" manda a los jugadores nuevos (ver onLoginFirst/onLoginLast). */
    public static final double TP_X = 100.5, TP_Y = -60, TP_Z = 100.5;

    // Eventos por jugador (en minúsculas), en orden de llegada
    private static final Map<String, List<String>> LOG = new ConcurrentHashMap<>();

    public DirectAuthTest() {
        register(0, ActionPayload.class, ActionPayload::value, ActionPayload::new, NetworkDirection.PLAY_TO_SERVER,
            (payload, ctx) -> log(ctx.get().getSender(), "action:" + payload.value()));
        register(1, HelloPayload.class, HelloPayload::value, HelloPayload::new, NetworkDirection.PLAY_TO_SERVER,
            (payload, ctx) -> log(ctx.get().getSender(), "hello:" + payload.value()));
        register(2, ScreenPayload.class, ScreenPayload::value, ScreenPayload::new, NetworkDirection.PLAY_TO_CLIENT, DirectAuthTest::ignore);
        register(3, DataPayload.class, DataPayload::value, DataPayload::new, NetworkDirection.PLAY_TO_CLIENT, DirectAuthTest::ignore);
        ARCHITECTURY.addListener((NetworkEvent.ClientCustomPayloadEvent event) -> {
            ResourceLocation id = event.getPayload().readResourceLocation();
            String value = event.getPayload().readUtf();
            ServerPlayer sender = event.getSource().get().getSender();
            event.getSource().get().enqueueWork(() -> log(sender, "arch:" + id.getPath() + ":" + value));
            event.getSource().get().setPacketHandled(true);
        });
        MinecraftForge.EVENT_BUS.addListener(DirectAuthTest::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, DirectAuthTest::onLoginFirst);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, DirectAuthTest::onLoginLast);
        // Como una mod que lee el chat (la IA de aldeanos de MCA): lo ve antes que nadie, incluso si luego se cancela
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, (ServerChatEvent event) -> log(event.getPlayer(), "chat:" + event.getRawText()));
    }

    /** Un mensaje de un String; los del cliente se procesan en el hilo del servidor. */
    private static <T> void register(int index, Class<T> type, Function<T, String> value, Function<String, T> create,
            NetworkDirection direction, BiConsumer<T, Supplier<NetworkEvent.Context>> handler) {
        CHANNEL.messageBuilder(type, index, direction)
            .encoder((payload, buf) -> buf.writeUtf(value.apply(payload)))
            .decoder((FriendlyByteBuf buf) -> create.apply(buf.readUtf()))
            .consumerMainThread(handler)
            .add();
    }

    private static <T> void ignore(T payload, Supplier<NetworkEvent.Context> ctx) {
    }

    private static void log(Player player, String event) {
        LOG.computeIfAbsent(player.getGameProfile().getName().toLowerCase(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static void send(ServerPlayer player, Object payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    /** Menú de cofre que anota los clics en botones (como el selector de raza de Tensura). */
    private static MenuProvider menu(String tag) {
        return new SimpleMenuProvider((id, inventory, p) ->
            new ChestMenu(MenuType.GENERIC_9x3, id, inventory, new SimpleContainer(27), 3) {
                @Override
                public boolean clickMenuButton(Player clicker, int button) {
                    log(clicker, "button:" + tag + ":" + button);
                    return true;
                }
            }, Component.literal("datest " + tag));
    }

    private static void openMenu(ServerPlayer player, String tag) {
        var result = player.openMenu(menu(tag));
        log(player, "openMenu:" + tag + ":" + (result.isPresent() ? "opened" : "empty"));
    }

    /** Lo mismo con la variante de Forge, que envía el menú por su propio canal (fml:play). */
    private static void openScreen(ServerPlayer player, String tag) {
        NetworkHooks.openScreen(player, menu(tag));
        log(player, "openScreen:" + tag);
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
            send(player, new ScreenPayload("login"));
            send(player, new DataPayload("login"));
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
                            send(EntityArgument.getPlayer(ctx, "player"), new ScreenPayload(StringArgumentType.getString(ctx, "value")));
                            reply(ctx.getSource(), "datest screen sent");
                            return 1;
                        }))))
            .then(Commands.literal("data")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("value", StringArgumentType.word())
                        .executes(ctx -> {
                            send(EntityArgument.getPlayer(ctx, "player"), new DataPayload(StringArgumentType.getString(ctx, "value")));
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
                        }))))
            .then(Commands.literal("screenmenu")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(ctx -> {
                            openScreen(EntityArgument.getPlayer(ctx, "player"), StringArgumentType.getString(ctx, "tag"));
                            reply(ctx.getSource(), "datest screenmenu requested");
                            return 1;
                        })))));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
