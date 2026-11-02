package com.marcp.directauth.testmod.mixin;

import com.marcp.directauth.testmod.DirectAuthTest;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Como una mod que lee el chat al principio de handleChat (la IA de aldeanos de MCA): lo ve antes
 * que nadie, también lo que luego se cancele.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinChatListener {

    @Inject(method = "handleChat", at = @At("HEAD"))
    private void directAuthTest$readChat(ServerboundChatPacket packet, CallbackInfo ci) {
        DirectAuthTest.log(((ServerGamePacketListenerImpl) (Object) this).player, "chat:" + packet.message());
    }
}
