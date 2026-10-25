package com.marcp.directauth.mixin;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bloquea en su origen las acciones de inventario de un jugador sin autenticar (tirar objetos,
 * cambiar a la otra mano, botón central, clics en contenedores, ranura de creativo). Se hace sobre los paquetes y no sobre
 * ItemTossEvent porque ese evento también lo disparan drops del propio juego (cerrar menús al
 * desconectarse, /give), que no deben alterarse.
 *
 * Se inyecta DESPUÉS de ensureRunningOnSameThread: antes de esa llamada el handler aún se está
 * ejecutando en el hilo de red.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow public ServerPlayer player;

    @Unique
    private static final String ENSURE_MAIN_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = ENSURE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void directAuth$blockDropWhileUnauthenticated(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        if ((action == ServerboundPlayerActionPacket.Action.DROP_ITEM || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)
                && PlayerRestrictionHandler.isRestricted(this.player)) {
            ci.cancel();
            // El cliente ya quitó el objeto de su vista: reenviarle el inventario real
            this.player.containerMenu.sendAllDataToRemote();
            this.player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgNoDrop));
        } else if (action == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND && PlayerRestrictionHandler.isRestricted(this.player)) {
            // Tecla F: tampoco se reordena el inventario de la cuenta
            ci.cancel();
            this.player.inventoryMenu.sendAllDataToRemote();
        }
    }

    @Inject(method = "handlePickItem", at = @At(value = "INVOKE", target = ENSURE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void directAuth$blockPickItemWhileUnauthenticated(ServerboundPickItemPacket packet, CallbackInfo ci) {
        // Botón central sobre un bloque: lleva un objeto del inventario a la barra rápida
        if (PlayerRestrictionHandler.isRestricted(this.player)) {
            ci.cancel();
            this.player.inventoryMenu.sendAllDataToRemote();
            this.player.connection.send(new ClientboundSetCarriedItemPacket(this.player.getInventory().selected));
        }
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = ENSURE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void directAuth$blockContainerClickWhileUnauthenticated(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(this.player)) {
            ci.cancel();
            this.player.containerMenu.sendAllDataToRemote();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = ENSURE_MAIN_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void directAuth$blockCreativeSlotWhileUnauthenticated(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (PlayerRestrictionHandler.isRestricted(this.player)) {
            ci.cancel();
            this.player.inventoryMenu.sendAllDataToRemote();
        }
    }
}
