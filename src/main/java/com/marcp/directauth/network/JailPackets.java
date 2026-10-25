package com.marcp.directauth.network;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.mixin.ServerGamePacketListenerAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.Optional;
import java.util.Set;

/**
 * Paquetes vanilla que se aceptan de un jugador en la jaula; el resto se descartan. Es una lista
 * de permitidos y no de prohibidos para cubrir también los paquetes que añadan versiones futuras.
 * <p>
 * Algunos paquetes permitidos se procesan para que la propia jaula los rechace con su efecto
 * visible corregido en el cliente: interactuar, usar objetos y romper los cancelan los eventos de
 * {@code PlayerRestrictionHandler} (que reenvían el bloque al cliente), y tirar o cambiar objetos de
 * mano, el botón central, los clics en el inventario y la ranura de creativo los cancela
 * {@code MixinServerGamePacketListenerImpl} (que resincroniza el inventario).
 * Fuera quedan, entre otros, los bloques de comandos, estructuras y puzles, la dificultad, las
 * consultas NBT, editar libros, colocar recetas y los botones de los menús.
 */
public final class JailPackets {
    private static final Set<Class<?>> ALLOWED = Set.of(
        // Conexión y ajustes del cliente
        ServerboundKeepAlivePacket.class, ServerboundPongPacket.class, ServerboundPingRequestPacket.class,
        ServerboundClientInformationPacket.class, ServerboundResourcePackPacket.class,
        ServerboundConfigurationAcknowledgedPacket.class, ServerboundChunkBatchReceivedPacket.class,
        // Paquetes de mods: los retiene JailTraffic
        ServerboundCustomPayloadPacket.class,
        // Comandos (los filtra PlayerRestrictionHandler: solo /register, /login, /online); el chat
        // no se procesa (ver dropChat)
        ServerboundChatCommandPacket.class, ServerboundChatCommandSignedPacket.class, ServerboundChatAckPacket.class, ServerboundChatSessionUpdatePacket.class, ServerboundCommandSuggestionPacket.class,
        // Movimiento (el ancla lo corrige) y estado del jugador
        ServerboundMovePlayerPacket.class, ServerboundAcceptTeleportationPacket.class, ServerboundPlayerInputPacket.class,
        ServerboundPlayerCommandPacket.class, ServerboundPlayerAbilitiesPacket.class, ServerboundMoveVehiclePacket.class,
        ServerboundPaddleBoatPacket.class, ServerboundClientCommandPacket.class, ServerboundSwingPacket.class,
        // Ranura seleccionada y pantallas del cliente
        ServerboundSetCarriedItemPacket.class, ServerboundContainerClosePacket.class,
        ServerboundSeenAdvancementsPacket.class, ServerboundRecipeBookChangeSettingsPacket.class,
        ServerboundRecipeBookSeenRecipePacket.class,
        // Los rechaza la jaula corrigiendo el cliente (ver arriba)
        ServerboundInteractPacket.class, ServerboundUseItemPacket.class, ServerboundUseItemOnPacket.class,
        ServerboundPlayerActionPacket.class, ServerboundPickItemPacket.class, ServerboundContainerClickPacket.class,
        ServerboundSetCreativeModeSlotPacket.class
    );

    private JailPackets() {
    }

    /**
     * Mensaje de chat (no un comando) de un jugador en la jaula: no lo ve nadie, ni siquiera otras
     * mods que leen el chat antes de ServerChatEvent (la IA de aldeanos de MCA engancha el principio
     * de handleChat y lo envía a un servicio externo). Se valida como en vanilla handleChat, sin
     * difundirlo: así los mensajes vistos y la cadena de mensajes firmados del servidor avanzan
     * igual que en el cliente, y el chat sigue funcionando tras /login (con el chat firmado, un
     * eslabón sin avanzar rechazaría su siguiente mensaje). Hilo de red.
     */
    public static void dropChat(ServerGamePacketListenerImpl connection, ServerboundChatPacket chat) {
        ServerGamePacketListenerAccessor vanilla = (ServerGamePacketListenerAccessor) connection;
        Optional<LastSeenMessages> lastSeen = vanilla.callUnpackAndApplyLastSeen(chat.lastSeenMessages());
        if (lastSeen.isEmpty()) return; // vanilla ya ha desconectado
        ServerPlayer player = connection.player;
        // tryHandleChat lo pasa al hilo del servidor con server.execute, que al detenerse lo
        // ejecutaría aquí mismo: entonces se descarta (como AuthTasks.onServerThread)
        vanilla.callTryHandleChat(chat.message(), () -> {
            if (!player.server.isSameThread()) return;
            try {
                vanilla.callGetSignedMessage(chat, lastSeen.get());
            } catch (SignedMessageChain.DecodeException e) {
                vanilla.callHandleMessageDecodeFailure(e);
                return;
            }
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgUseCommands));
        });
    }

    public static boolean allowed(Packet<?> packet) {
        // Las variantes (p. ej. ServerboundMovePlayerPacket.Pos) son subclases
        for (Class<?> type = packet.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (ALLOWED.contains(type)) return true;
        }
        return false;
    }
}
