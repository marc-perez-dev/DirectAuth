package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Un jugador sin autenticar no puede abrir contenedores él mismo, así que cualquier menú que se le
 * abra lo abre el servidor (p. ej. la elección de raza de Tensura al entrar) y le taparía el chat
 * antes de /login. Se aplaza hasta que se autentique (ver JailTraffic). openMenu(MenuProvider) de
 * vanilla delega en esta variante de NeoForge. Quien lo abre recibe "no abierto", así que lo que
 * enviara después con el id del menú no se repite al abrirlo (los menús de comercio, que lo hacen,
 * no se pueden abrir desde la jaula).
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerOpenMenu {

    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;",
        at = @At("HEAD"), cancellable = true)
    private void directAuth$deferMenuWhileUnauthenticated(MenuProvider menu, Consumer<RegistryFriendlyByteBuf> extraData,
            CallbackInfoReturnable<OptionalInt> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (menu != null && PlayerRestrictionHandler.isRestricted(self) && JailTraffic.deferMenu(self, menu, extraData)) {
            cir.setReturnValue(OptionalInt.empty());
        }
    }
}
