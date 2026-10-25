package com.marcp.directauth.network;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.MenuProvider;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Tráfico de un jugador en la jaula (sin autenticar) con otras mods. Hasta que se autentica:
 * <ul>
 *   <li>Lo que su cliente envía a otras mods se retiene y se procesa al autenticarse (solo el último
 *   paquete de cada tipo: Epic Fight, por ejemplo, envía uno por tick). Si se desconecta antes, se
 *   descarta: un impostor no puede actuar en nombre de la cuenta.</li>
 *   <li>Las órdenes de abrir pantallas de otras mods ({@code deferredScreenPayloads}) y los menús
 *   que abre el servidor se le entregan al autenticarse, para que no le tapen el chat antes.</li>
 * </ul>
 * Los paquetes del cliente llegan por el hilo de red y la autenticación ocurre en el del servidor:
 * cada jugador tiene su cola bajo su propio candado, y {@link #release} la cierra.
 * <p>
 * En Forge una mod suele enviar todos sus mensajes por un mismo canal (SimpleChannel), con el
 * índice del mensaje al principio: el tipo de un paquete es entonces {@code canal#índice}, o
 * {@code canal#id} si el canal lleva dentro el id del mensaje (ver {@link #payloadType}).
 */
public final class JailTraffic {
    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
    /** Canales con el índice del mensaje al principio, y cuántos bytes ocupa (MixinSimpleChannel, MixinPixelmonSimpleChannel). */
    private static final Map<ResourceLocation, Integer> INDEXED_CHANNELS = new ConcurrentHashMap<>();
    /** Canal único de Architectury: cada mensaje empieza por su propio id (FTB Quests, FTB Teams, KubeJS...). */
    private static final ResourceLocation ARCHITECTURY = new ResourceLocation("architectury", "network");

    /** extraData no es null si se abrió con NetworkHooks.openScreen (y se vuelve a abrir así). */
    private record MenuRequest(MenuProvider provider, @Nullable Consumer<FriendlyByteBuf> extraData) {}

    private record Deferred(Packet<?> packet, @Nullable PacketSendListener sendListener) {}

    /** Lo retenido de una conexión concreta (una reconexión empieza de cero). */
    private static final class Held {
        final ServerGamePacketListenerImpl connection;
        final Map<String, ServerboundCustomPayloadPacket> fromClient = new LinkedHashMap<>();
        final Map<String, Deferred> toClient = new LinkedHashMap<>();
        @Nullable MenuRequest menu;
        boolean released;

        Held(ServerGamePacketListenerImpl connection) {
            this.connection = connection;
        }

        boolean isEmpty() {
            return fromClient.isEmpty() && toClient.isEmpty() && menu == null;
        }
    }

    private JailTraffic() {
    }

    /**
     * Guarda algo para una conexión en la jaula. Devuelve false (y no guarda nada) si la conexión ya
     * se cerró, ya se liberó o el jugador ya está autenticado: entonces debe seguir su curso normal.
     * Se comprueba bajo el candado porque release() y discard() corren en otro hilo.
     */
    private static boolean store(ServerGamePacketListenerImpl connection, Consumer<Held> add) {
        UUID uuid = connection.player.getUUID();
        Held held = HELD.compute(uuid, (key, current) -> {
            if (current != null && current.connection == connection) return current;
            if (current != null) drop(current); // de una conexión anterior que no llegó a descartarse
            return new Held(connection);
        });
        synchronized (held) {
            // Conexión cerrada: discard() ya pasó o va a pasar, y no debe quedar nada retenido
            if (held.released || !connection.connection.isConnected() || !PlayerRestrictionHandler.isRestricted(connection.player)) {
                if (held.isEmpty()) HELD.remove(uuid, held);
                return false;
            }
            add.accept(held);
            return true;
        }
    }

    /** Payloads que nunca se retienen ni se aplazan: los de Minecraft (registro de canales, marca) y Forge. */
    private static boolean isLoaderPayload(ResourceLocation id) {
        String namespace = id.getNamespace();
        return namespace.equals("minecraft") || namespace.equals("forge") || namespace.equals("fml");
    }

    /**
     * Lo llaman los mixins de los SimpleChannel al crearse cada canal: el de Forge escribe el índice
     * en un byte; el de Pixelmon, en dos.
     */
    public static void registerIndexedChannel(ResourceLocation channel, int indexBytes) {
        INDEXED_CHANNELS.put(channel, indexBytes);
    }

    /**
     * Tipo de un paquete de mod: {@code canal#índice} en un SimpleChannel (el índice con el que la
     * mod registró el mensaje), {@code architectury:network#id} en Architectury, y el canal en el
     * resto, que suelen usar un canal por tipo de paquete.
     */
    private static String payloadType(ResourceLocation channel, FriendlyByteBuf data) {
        Integer indexBytes = INDEXED_CHANNELS.get(channel);
        if (indexBytes != null && data.readableBytes() >= indexBytes) {
            int start = data.readerIndex();
            return channel + "#" + (indexBytes == 2 ? data.getUnsignedShort(start) : data.getUnsignedByte(start));
        }
        if (channel.equals(ARCHITECTURY)) {
            try {
                return channel + "#" + new FriendlyByteBuf(data.duplicate()).readResourceLocation();
            } catch (RuntimeException e) {
                // Mal formado: lo trata la mod (o lo rechaza) como siempre; aquí cuenta como el canal
            }
        }
        return channel.toString();
    }

    /** Una entrada de la config es un tipo ({@code canal#índice}) o un canal entero. */
    private static boolean listed(List<String> entries, ResourceLocation channel, String type) {
        return entries.contains(type) || entries.contains(channel.toString());
    }

    /**
     * Payload de una mod enviado por el cliente (hilo de red). Devuelve true si queda retenido y no
     * debe procesarse ahora.
     */
    public static boolean holdFromClient(ServerGamePacketListenerImpl connection, ServerboundCustomPayloadPacket packet) {
        ResourceLocation channel = packet.getIdentifier();
        if (isLoaderPayload(channel)) return false;
        if (!PlayerRestrictionHandler.isRestricted(connection.player)) {
            // Recién autenticado pero release() aún no ha corrido: este paquete es más nuevo que el
            // retenido del mismo tipo, que ya no debe procesarse después
            Held held = HELD.get(connection.player.getUUID());
            if (held != null && held.connection == connection) {
                String type = payloadType(channel, packet.getData());
                synchronized (held) {
                    if (!held.released) releaseBuffer(held.fromClient.remove(type));
                }
            }
            return false;
        }
        String type = payloadType(channel, packet.getData());
        if (listed(DirectAuth.getConfig().heldPayloadExceptions, channel, type)) return false;
        return store(connection, held -> {
            // Vanilla libera el buffer del paquete al terminar de procesarlo: lo conservamos
            packet.getData().retain();
            releaseBuffer(held.fromClient.remove(type)); // el último de cada tipo, en orden de llegada
            held.fromClient.put(type, packet);
        });
    }

    private static void releaseBuffer(@Nullable ServerboundCustomPayloadPacket packet) {
        if (packet != null) packet.getData().release();
    }

    /**
     * Paquete que el servidor va a enviar al jugador. Devuelve true si se ha aplazado y el envío
     * original no debe seguir. Solo mira los payloads sueltos: en 1.20.1 ni Forge ni vanilla meten
     * payloads de mods en un ClientboundBundlePacket (en NeoForge 1.21.1 sí, y main los separa).
     */
    public static boolean deferToClient(ServerGamePacketListenerImpl connection, Packet<?> packet, @Nullable PacketSendListener sendListener) {
        if (!(packet instanceof ClientboundCustomPayloadPacket custom) || !PlayerRestrictionHandler.isRestricted(connection.player)) return false;
        ResourceLocation channel = custom.getIdentifier();
        if (isLoaderPayload(channel)) return false;
        String type = payloadType(channel, custom.getData());
        boolean defer = listed(DirectAuth.getConfig().deferredScreenPayloads, channel, type);
        if (DirectAuth.getConfig().logJailPayloads) {
            DirectAuth.LOGGER.info("DirectAuth: payload {} to unauthenticated {}{}", type,
                connection.player.getGameProfile().getName(), defer ? " (deferred until they log in)" : "");
        }
        return defer && store(connection, held -> {
            held.toClient.remove(type); // Pixelmon reenvía su pantalla cada 5 s: basta con la última
            held.toClient.put(type, new Deferred(custom, sendListener));
        });
    }

    /**
     * Menú que el servidor abre a un jugador en la jaula: se abrirá al autenticarse (el último).
     * Devuelve false si el jugador ya no está en la jaula y el menú debe abrirse ahora.
     * Quien abre el menú recibe "no abierto": lo que enviara después con su id no se repite.
     *
     * @param extraData los datos de NetworkHooks.openScreen, o null si se abrió con openMenu
     */
    public static boolean deferMenu(ServerPlayer player, MenuProvider provider, @Nullable Consumer<FriendlyByteBuf> extraData) {
        return store(player.connection, held -> held.menu = new MenuRequest(provider, extraData));
    }

    /**
     * El jugador acaba de autenticarse (hilo del servidor): le llega lo aplazado y se procesa lo que
     * envió su cliente mientras estaba en la jaula.
     */
    public static void release(ServerPlayer player) {
        Held held = HELD.get(player.getUUID());
        if (held == null) return;
        List<Deferred> toClient;
        List<ServerboundCustomPayloadPacket> fromClient;
        MenuRequest menu;
        synchronized (held) {
            HELD.remove(player.getUUID(), held);
            held.released = true;
            toClient = List.copyOf(held.toClient.values());
            fromClient = List.copyOf(held.fromClient.values());
            menu = held.menu;
        }
        if (player.connection != held.connection || player.hasDisconnected()) {
            fromClient.forEach(JailTraffic::releaseBuffer);
            return;
        }
        for (Deferred deferred : toClient) player.connection.send(deferred.packet(), deferred.sendListener());
        for (ServerboundCustomPayloadPacket packet : fromClient) {
            try {
                NetworkHooks.onCustomPayload(packet, player.connection.connection);
            } catch (RuntimeException e) {
                DirectAuth.LOGGER.error("DirectAuth: Could not process held payload {} from {}",
                    packet.getIdentifier(), player.getGameProfile().getName(), e);
            } finally {
                releaseBuffer(packet);
            }
        }
        if (menu != null) {
            if (menu.extraData() != null) {
                NetworkHooks.openScreen(player, menu.provider(), menu.extraData());
            } else {
                player.openMenu(menu.provider());
            }
        }
    }

    /** El jugador se ha desconectado: lo retenido de esa conexión no se procesa nunca. */
    public static void discard(ServerPlayer player) {
        Held held = HELD.get(player.getUUID());
        if (held != null && held.connection == player.connection && HELD.remove(player.getUUID(), held)) drop(held);
    }

    /** Al detenerse el servidor. */
    public static void clear() {
        HELD.values().removeIf(held -> {
            drop(held);
            return true;
        });
    }

    private static void drop(Held held) {
        synchronized (held) {
            held.released = true; // store() ya no añade nada a esta cola
            held.fromClient.values().forEach(JailTraffic::releaseBuffer);
            held.fromClient.clear();
        }
    }
}
