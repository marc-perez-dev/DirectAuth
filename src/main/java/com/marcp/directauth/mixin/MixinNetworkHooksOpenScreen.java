package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraftforge.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * La otra forma de abrir un menú en Forge, con datos extra para el cliente: no pasa por
 * ServerPlayer.openMenu (ver MixinServerPlayerOpenMenu). Las otras variantes de openScreen delegan
 * en esta.
 */
@Mixin(value = NetworkHooks.class, remap = false)
public abstract class MixinNetworkHooksOpenScreen {

    @Inject(method = "openScreen(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)V",
        at = @At("HEAD"), cancellable = true)
    private static void directAuth$deferMenuWhileUnauthenticated(ServerPlayer player, MenuProvider menu, Consumer<FriendlyByteBuf> extraData,
            CallbackInfo ci) {
        if (menu != null && extraData != null && PlayerRestrictionHandler.isRestricted(player) && JailTraffic.deferMenu(player, menu, extraData)) {
            ci.cancel();
        }
    }
}
