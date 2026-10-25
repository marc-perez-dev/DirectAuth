package com.marcp.directauth.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundCustomPayloadPacket.class)
public interface ClientboundCustomPayloadPacketAccessor {
    // getData() (y getInternalData()) devuelven una copia entera del buffer en 1.20.1
    @Accessor("data")
    FriendlyByteBuf getDataWithoutCopy();
}
