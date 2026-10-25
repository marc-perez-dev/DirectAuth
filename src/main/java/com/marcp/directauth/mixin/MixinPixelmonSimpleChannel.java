package com.marcp.directauth.mixin;

import com.marcp.directauth.network.JailTraffic;
import net.minecraftforge.network.NetworkInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Compatibilidad con Pixelmon: tiene su propia copia de SimpleChannel (no pasa por
 * MixinSimpleChannel), que escribe el índice del mensaje en dos bytes. Sin esto, todo Pixelmon
 * sería un único tipo para JailTraffic: aplazar su pantalla de inicial aplazaría todo lo demás.
 *
 * @Pseudo + require = 0: sin Pixelmon instalado el mixin simplemente no se aplica. Los constructores
 * públicos delegan en este.
 */
@Pseudo
@Mixin(targets = "com.pixelmonmod.pixelmon.network.SimpleChannel", remap = false)
public abstract class MixinPixelmonSimpleChannel {

    @Inject(method = "<init>(Lnet/minecraftforge/network/NetworkInstance;Ljava/util/Optional;)V", at = @At("TAIL"), require = 0)
    private void directAuth$registerChannel(NetworkInstance instance, Optional<?> registryChangeNotify, CallbackInfo ci) {
        JailTraffic.registerIndexedChannel(instance.getChannelName(), 2);
    }
}
