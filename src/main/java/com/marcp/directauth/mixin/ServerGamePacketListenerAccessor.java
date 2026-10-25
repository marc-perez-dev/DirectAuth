package com.marcp.directauth.mixin;

import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.SignedMessageChain;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

/** Los pasos de vanilla handleChat, para validar un mensaje de chat que no se va a procesar (JailPackets.dropChat). */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerAccessor {
    @Invoker("unpackAndApplyLastSeen")
    Optional<LastSeenMessages> callUnpackAndApplyLastSeen(LastSeenMessages.Update update);

    @Invoker("tryHandleChat")
    void callTryHandleChat(String message, Runnable handler);

    @Invoker("getSignedMessage")
    PlayerChatMessage callGetSignedMessage(ServerboundChatPacket packet, LastSeenMessages lastSeenMessages) throws SignedMessageChain.DecodeException;

    @Invoker("handleMessageDecodeFailure")
    void callHandleMessageDecodeFailure(SignedMessageChain.DecodeException exception);
}
