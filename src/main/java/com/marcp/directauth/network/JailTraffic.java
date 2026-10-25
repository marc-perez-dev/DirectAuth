package com.marcp.directauth.network;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.MenuProvider;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
 */
public final class JailTraffic {
    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    private record MenuRequest(MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extraData) {}

    private record Deferred(Packet<?> packet, @Nullable PacketSendListener sendListener) {}

    /** Lo retenido de una conexión concreta (una reconexión empieza de cero). */
    private static final class Held {
        final ServerGamePacketListenerImpl connection;
        final Map<ResourceLocation, ServerboundCustomPayloadPacket> fromClient = new LinkedHashMap<>();
        final Map<ResourceLocation, Deferred> toClient = new LinkedHashMap<>();
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
        if (!connection.getConnection().isConnected()) return false; // discard() ya pasó o va a pasar
        UUID uuid = connection.player.getUUID();
        Held held = HELD.compute(uuid, (key, current) -> current == null || current.connection != connection ? new Held(connection) : current);
        synchronized (held) {
            if (held.released || !PlayerRestrictionHandler.isRestricted(connection.player)) {
                if (held.isEmpty()) HELD.remove(uuid, held);
                return false;
            }
            add.accept(held);
            return true;
        }
    }

    /** Payloads que nunca se retienen: los de Minecraft no llegan aquí; estos son de NeoForge. */
    private static boolean isLoaderPayload(ResourceLocation id) {
        return id.getNamespace().equals("neoforge");
    }

    /**
     * Payload de una mod enviado por el cliente (hilo de red). Devuelve true si queda retenido y no
     * debe procesarse ahora.
     */
    public static boolean holdFromClient(ServerGamePacketListenerImpl connection, ServerboundCustomPayloadPacket packet) {
        ResourceLocation id = packet.payload().type().id();
        if (!PlayerRestrictionHandler.isRestricted(connection.player)) {
            // Recién autenticado pero release() aún no ha corrido: este paquete es más nuevo que el
            // retenido del mismo tipo, que ya no debe procesarse después
            Held held = HELD.get(connection.player.getUUID());
            if (held != null && held.connection == connection) {
                synchronized (held) {
                    if (!held.released) held.fromClient.remove(id);
                }
            }
            return false;
        }
        if (isLoaderPayload(id) || DirectAuth.getConfig().heldPayloadExceptions.contains(id.toString())) return false;
        return store(connection, held -> {
            held.fromClient.remove(id); // el último de cada tipo, en orden de llegada
            held.fromClient.put(id, packet);
        });
    }

    /**
     * Paquete que el servidor va a enviar al jugador. Devuelve true si se ha aplazado (o se ha
     * enviado sin la parte aplazada, en un bundle) y el envío original no debe seguir.
     */
    public static boolean deferToClient(ServerGamePacketListenerImpl connection, Packet<?> packet, @Nullable PacketSendListener sendListener) {
        if (!PlayerRestrictionHandler.isRestricted(connection.player)) return false;
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            return shouldDefer(connection, custom) && defer(connection, custom, sendListener);
        }
        // PacketDistributor junta varios payloads en un bundle
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> rest = new ArrayList<>();
            List<ClientboundCustomPayloadPacket> toDefer = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundCustomPayloadPacket custom && shouldDefer(connection, custom)) {
                    toDefer.add(custom);
                } else {
                    rest.add(sub);
                }
            }
            if (toDefer.isEmpty()) return false;
            // El aviso de envío va con lo que se envía ahora o, si se aplaza todo, con el último
            int deferred = 0;
            for (int i = 0; i < toDefer.size(); i++) {
                ClientboundCustomPayloadPacket custom = toDefer.get(i);
                boolean last = i == toDefer.size() - 1;
                if (defer(connection, custom, last && rest.isEmpty() ? sendListener : null)) {
                    deferred++;
                } else {
                    rest.add(custom);
                }
            }
            if (deferred == 0) return false; // se autenticó entretanto: el bundle sigue tal cual
            if (!rest.isEmpty()) connection.send(new ClientboundBundlePacket(rest), sendListener);
            return true;
        }
        return false;
    }

    private static boolean shouldDefer(ServerGamePacketListenerImpl connection, ClientboundCustomPayloadPacket packet) {
        ResourceLocation id = packet.payload().type().id();
        if (id.getNamespace().equals("minecraft") || isLoaderPayload(id)) return false;
        boolean defer = DirectAuth.getConfig().deferredScreenPayloads.contains(id.toString());
        if (DirectAuth.getConfig().logJailPayloads) {
            DirectAuth.LOGGER.info("DirectAuth: payload {} to unauthenticated {}{}", id,
                connection.player.getGameProfile().getName(), defer ? " (deferred until they log in)" : "");
        }
        return defer;
    }

    private static boolean defer(ServerGamePacketListenerImpl connection, ClientboundCustomPayloadPacket packet, @Nullable PacketSendListener sendListener) {
        ResourceLocation id = packet.payload().type().id();
        return store(connection, held -> {
            held.toClient.remove(id); // Pixelmon reenvía su pantalla cada 5 s: basta con la última
            held.toClient.put(id, new Deferred(packet, sendListener));
        });
    }

    /**
     * Menú que el servidor abre a un jugador en la jaula: se abrirá al autenticarse (el último).
     * Devuelve false si el jugador ya no está en la jaula y el menú debe abrirse ahora.
     * Quien abre el menú recibe "no abierto": lo que enviara después con su id no se repite.
     */
    public static boolean deferMenu(ServerPlayer player, MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extraData) {
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
        if (player.connection != held.connection || player.hasDisconnected()) return;
        for (Deferred deferred : toClient) player.connection.send(deferred.packet(), deferred.sendListener());
        for (ServerboundCustomPayloadPacket packet : fromClient) {
            try {
                NetworkRegistry.handleModdedPayload(player.connection, packet);
            } catch (RuntimeException e) {
                DirectAuth.LOGGER.error("DirectAuth: Could not process held payload {} from {}",
                    packet.payload().type().id(), player.getGameProfile().getName(), e);
            }
        }
        if (menu != null) player.openMenu(menu.provider(), menu.extraData());
    }

    /** El jugador se ha desconectado: lo retenido de esa conexión no se procesa nunca. */
    public static void discard(ServerPlayer player) {
        HELD.computeIfPresent(player.getUUID(), (uuid, held) -> held.connection == player.connection ? null : held);
    }

    /** Al detenerse el servidor. */
    public static void clear() {
        HELD.clear();
    }
}
