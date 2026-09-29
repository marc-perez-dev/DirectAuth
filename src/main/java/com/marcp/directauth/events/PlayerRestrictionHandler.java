package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerRestrictionHandler {
    
    // Posición (y dimensión) en la que se mantiene quieto a cada jugador sin autenticar
    private record Anchor(ResourceKey<Level> dimension, Vec3 position) {}

    private static final Map<UUID, Anchor> anchorPositions = new ConcurrentHashMap<>();

    public static void removeAnchor(ServerPlayer player) {
        anchorPositions.remove(player.getUUID());
    }

    /** Olvida todas las anclas (al detenerse el servidor). */
    public static void clearAnchors() {
        anchorPositions.clear();
    }

    /** Levanta las restricciones de un jugador que acaba de autenticarse. */
    public static void release(ServerPlayer player) {
        removeAnchor(player);
        // Resincronizar HUD de efectos (limpiados al entrar en limbo)
        resyncEffectsToClient(player);
    }

    public static void hideEffectsFromClient(ServerPlayer player) {
        for (MobEffectInstance instance : player.getActiveEffects()) {
            player.connection.send(new ClientboundRemoveMobEffectPacket(player.getId(), instance.getEffect()));
        }
    }

    public static void resyncEffectsToClient(ServerPlayer player) {
        for (MobEffectInstance instance : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), instance, false));
        }
    }
    
    /**
     * true si la entidad es un jugador real que aún no se ha autenticado. Las FakePlayer
     * (máquinas de otros mods: deployers, miners...) nunca se autentican y no deben bloquearse.
     */
    public static boolean isRestricted(Object entity) {
        return entity instanceof ServerPlayer player
            && !(player instanceof FakePlayer)
            && DirectAuth.getLoginManager() != null
            && !DirectAuth.getLoginManager().isAuthenticated(player);
    }

    private boolean isNotAuth(Object entity) {
        return isRestricted(entity);
    }

    // --- NUEVO: Bloquear Regeneración de Salud ---
    @SubscribeEvent
    public void onEntityHeal(LivingHealEvent event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true); // Nadie se cura sin contraseña
        }
    }

    // --- 1. Movimiento y Tick ---
    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            
            if (isNotAuth(player)) {
                // Verificar Timeout (Kick) cada 20 ticks (1 segundo) para no saturar
                if (player.tickCount % 20 == 0) {
                    if (DirectAuth.getLoginManager().hasTimedOut(player)) {
                        player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgTimeout));
                        return; // Salimos para no ejecutar el resto de lógica
                    }
                }

                UUID uuid = player.getUUID();

                Anchor anchor = anchorPositions.computeIfAbsent(uuid,
                    id -> new Anchor(player.level().dimension(), player.position()));

                player.setDeltaMovement(Vec3.ZERO);

                Vec3 pos = anchor.position();
                if (player.level().dimension() != anchor.dimension()) {
                    // Cambió de dimensión (portal, respawn...): devolverlo a la dimensión del ancla
                    ServerLevel anchorLevel = player.getServer().getLevel(anchor.dimension());
                    if (anchorLevel != null) {
                        player.teleportTo(anchorLevel, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
                    }
                } else if (player.position().distanceToSqr(pos) > 2.25) {
                    player.teleportTo(pos.x, pos.y, pos.z);
                }
                
                // Removed player.clearFire() as fire ticks will be restored on authentication.
                
                if (player.tickCount % 100 == 0) {
                    player.displayClientMessage(
                        Component.literal(DirectAuth.getConfig().getLang().msgAuthReminder),
                        true 
                    );
                }
            } 
            else if (anchorPositions.containsKey(player.getUUID())) {
                anchorPositions.remove(player.getUUID());
            }
        }
    }

    // --- (El resto de eventos se mantienen igual que en tu código original) ---
    
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (isNotAuth(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onInteractEntitySpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        // INTERACT_AT: lo usan p. ej. los armor stands para cambiar equipo
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onMount(EntityMountEvent event) {
        if (event.isMounting() && isNotAuth(event.getEntityMounting())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (isNotAuth(event.getPlayer())) event.setCanPickup(TriState.FALSE);
    }
    @SubscribeEvent
    public void onXpPickup(PlayerXpEvent.PickupXp event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Pre event) {
        if (isNotAuth(event.getEntity())) event.setNewDamage(0);
    }
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        // Los comandos no pasan por aquí (van por CommandEvent), así que todo el chat se bloquea
        if (isNotAuth(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgUseCommands));
        }
    }
    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player) {
            if (isNotAuth(player)) {
                String input = event.getParseResults().getReader().getString();
                String[] parts = input.trim().split(" ");
                String cmd = parts.length > 0 ? parts[0] : "";
                
                if (!cmd.equalsIgnoreCase("register") && 
                    !cmd.equalsIgnoreCase("login") && 
                    !cmd.equalsIgnoreCase("online")) {
                    event.setCanceled(true);
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgUseCommands));
                }
            }
        }
    }
}