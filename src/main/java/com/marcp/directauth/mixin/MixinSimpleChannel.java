package com.marcp.directauth.mixin;

import com.marcp.directauth.network.JailTraffic;
import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Anota qué canales son SimpleChannel: sus mensajes llevan el índice del tipo en el primer byte, y
 * JailTraffic los distingue por {@code canal#índice}. Los constructores públicos delegan en este.
 */
@Mixin(value = SimpleChannel.class, remap = false)
public abstract class MixinSimpleChannel {

    @Inject(method = "<init>(Lnet/minecraftforge/network/NetworkInstance;Ljava/util/Optional;)V", at = @At("TAIL"))
    private void directAuth$registerChannel(NetworkInstance instance, Optional<?> registryChangeNotify, CallbackInfo ci) {
        JailTraffic.registerIndexedChannel(instance.getChannelName(), 1);
    }
}
