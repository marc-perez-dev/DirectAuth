package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.network.JailTraffic;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Un jugador sin autenticar no puede abrir contenedores él mismo, así que cualquier menú que se le
 * abra lo abre el servidor (p. ej. la elección de raza de Tensura al entrar) y le taparía el chat
 * antes de /login. Se aplaza hasta que se autentique (ver JailTraffic). Los menús de Forge con datos
 * extra no pasan por aquí: ver MixinNetworkHooksOpenScreen. Quien lo abre recibe "no abierto", así que lo que
 * enviara después con el id del menú no se repite al abrirlo (los menús de comercio, que lo hacen,
 * no se pueden abrir desde la jaula).
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayerOpenMenu {

    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void directAuth$deferMenuWhileUnauthenticated(MenuProvider menu, CallbackInfoReturnable<OptionalInt> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (menu != null && PlayerRestrictionHandler.isRestricted(self) && JailTraffic.deferMenu(self, menu, null)) {
            cir.setReturnValue(OptionalInt.empty());
        }
    }
}
