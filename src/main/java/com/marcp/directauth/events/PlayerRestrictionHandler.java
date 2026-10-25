package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.network.JailTraffic;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
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
        // Pantallas y menús de otras mods aplazados, y lo que su cliente les envió en la jaula
        JailTraffic.release(player);
    }

    public static void hideEffectsFromClient(ServerPlayer player) {
        for (MobEffectInstance instance : player.getActiveEffects()) {
            player.connection.send(new ClientboundRemoveMobEffectPacket(player.getId(), instance.getEffect()));
        }
    }

    public static void resyncEffectsToClient(ServerPlayer player) {
        for (MobEffectInstance instance : player.getActiveEffects()) {
            player.connection.send(new ClientboundUpdateMobEffectPacket(player.getId(), instance));
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
    // Forge 1.20.1 no tiene EntityTickEvent. PlayerTickEvent no sirve: se dispara dentro de
    // ServerPlayer.doTick(), tras el cual la conexión devuelve al jugador a su posición de inicio
    // de tick (absMoveTo) y desharía el teleport al ancla. Al final del tick del servidor se
    // recorren los jugadores conectados, fuera del tick de la conexión.
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            // Como EntityTickEvent: solo jugadores que el nivel sigue tickeando (un jugador muerto
            // sale del nivel tras la animación de muerte y su tickCount deja de avanzar)
            if (!player.isRemoved()) tickPlayer(player);
        }
    }

    private void tickPlayer(ServerPlayer player) {
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
    public void onItemPickup(EntityItemPickupEvent event) {
        if (isNotAuth(event.getEntity())) event.setCanceled(true);
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
    public void onDamage(LivingHurtEvent event) {
        // LivingHurtEvent y no LivingDamageEvent: en 1.20.1 este último llega cuando ya se han
        // gastado los corazones de absorción (LivingDamageEvent.Pre de NeoForge llega antes)
        if (isNotAuth(event.getEntity())) event.setAmount(0);
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