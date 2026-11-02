package com.marcp.directauth.network;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.MenuProvider;
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

    /**
     * Límite de lo retenido del cliente por conexión: cada payload registrado puede ocupar mucho, y
     * sin límite una conexión sin autenticar podría llenar la memoria. Lo que no cabe se descarta.
     * Al entrar, un modpack grande envía unas decenas de tipos pequeños.
     */
    private static final int MAX_HELD_TYPES = 64;
    private static final int MAX_HELD_BYTES = 1 << 20;

    private record Deferred(Packet<?> packet, @Nullable PacketSendListener sendListener) {}

    /** Lo retenido de una conexión concreta (una reconexión empieza de cero). */
    private static final class Held {
        final ServerGamePacketListenerImpl connection;
        final Map<ResourceLocation, ServerboundCustomPayloadPacket> fromClient = new LinkedHashMap<>();
        final Map<ResourceLocation, Integer> fromClientSizes = new LinkedHashMap<>();
        int fromClientBytes;
        boolean warnedFull;
        final Map<ResourceLocation, Deferred> toClient = new LinkedHashMap<>();
        @Nullable MenuProvider menu;
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
        if (!connection.connection.isConnected()) return false; // discard() ya pasó o va a pasar
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

    /**
     * Payloads que nunca se retienen ni se aplazan: los de Minecraft (registro de canales, marca del
     * cliente...) y los de Fabric API y el protocolo común entre loaders ("c").
     */
    private static boolean isLoaderPayload(ResourceLocation id) {
        String namespace = id.getNamespace();
        return namespace.equals("minecraft") || namespace.equals("c")
            || namespace.equals("fabric") || namespace.startsWith("fabric-");
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
                    if (!held.released) removeFromClient(held, id);
                }
            }
            return false;
        }
        // Un id que ninguna mod registró llega como DiscardedPayload, y ni vanilla ni Fabric API
        // hacen nada con él: sigue su camino. Retenerlo dejaría a un cliente inventarse tipos.
        if (packet.payload() instanceof DiscardedPayload) return false;
        if (isLoaderPayload(id) || DirectAuth.getConfig().heldPayloadExceptions.contains(id.toString())) return false;
        int size = encodedSize(connection, packet);
        // Si no cabe se descarta igualmente: devuelve true
        return store(connection, held -> {
            Integer previous = held.fromClientSizes.get(id);
            long bytes = (long) held.fromClientBytes - (previous == null ? 0 : previous) + size;
            if (previous == null && held.fromClient.size() >= MAX_HELD_TYPES || bytes > MAX_HELD_BYTES) {
                if (!held.warnedFull) {
                    held.warnedFull = true;
                    DirectAuth.LOGGER.warn("DirectAuth: Too many mod payloads from unauthenticated {}, dropping the rest (first dropped: {})",
                        connection.player.getGameProfile().getName(), id);
                }
                return;
            }
            removeFromClient(held, id); // el último de cada tipo, en orden de llegada
            held.fromClient.put(id, packet);
            held.fromClientSizes.put(id, size);
            held.fromClientBytes = (int) bytes;
        });
    }

    private static void removeFromClient(Held held, ResourceLocation id) {
        held.fromClient.remove(id);
        Integer size = held.fromClientSizes.remove(id);
        if (size != null) held.fromClientBytes -= size;
    }

    /**
     * Lo que ocupa el payload en la red. Llega ya decodificado, así que se vuelve a codificar (solo
     * los de jugadores sin autenticar). Si no se puede, cuenta como si no cupiera.
     */
    private static int encodedSize(ServerGamePacketListenerImpl connection, ServerboundCustomPayloadPacket packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            ServerboundCustomPayloadPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(buffer, connection.player.server.registryAccess()), packet);
            return buffer.readableBytes();
        } catch (RuntimeException e) {
            return MAX_HELD_BYTES + 1;
        } finally {
            buffer.release();
        }
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
        // Un bundle puede llevar payloads (p. ej. los datos de una entidad al empezar a verla)
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
        if (isLoaderPayload(id)) return false;
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
    public static boolean deferMenu(ServerPlayer player, MenuProvider provider) {
        return store(player.connection, held -> held.menu = provider);
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
        MenuProvider menu;
        synchronized (held) {
            HELD.remove(player.getUUID(), held);
            held.released = true;
            toClient = List.copyOf(held.toClient.values());
            fromClient = List.copyOf(held.fromClient.values());
            menu = held.menu;
        }
        if (player.connection != held.connection || player.hasDisconnected()) return;
        // En este orden, como en main: pantallas, lo retenido y el menú aplazado.
        for (Deferred deferred : toClient) player.connection.send(deferred.packet(), deferred.sendListener());
        for (ServerboundCustomPayloadPacket packet : fromClient) {
            try {
                // Por el camino normal (Fabric API se lo pasa a la mod), ya sin retenerlo: el
                // jugador está autenticado
                player.connection.handleCustomPayload(packet);
            } catch (RuntimeException e) {
                DirectAuth.LOGGER.error("DirectAuth: Could not process held payload {} from {}",
                    packet.payload().type().id(), player.getGameProfile().getName(), e);
            }
        }
        if (menu != null) {
            // Fabric API pasa cada payload a su receptor con MinecraftServer.execute, que en el hilo
            // del servidor lo ejecuta en el acto, salvo dentro de una tarea de su cola (el resultado
            // de /login y /register llega así, ver AuthTasks): ahí lo encola. El menú va por el mismo
            // camino para abrirse después de esos receptores, como en main.
            ServerGamePacketListenerImpl connection = held.connection;
            AuthTasks.onServerThread(player.getServer()).execute(() -> {
                if (!connection.player.hasDisconnected()) connection.player.openMenu(menu);
            });
        }
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
