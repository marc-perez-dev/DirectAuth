package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent; // IMPORTANTE
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
    
    private static final Map<UUID, Vec3> anchorPositions = new ConcurrentHashMap<>();

    public static void removeAnchor(ServerPlayer player) {
        anchorPositions.remove(player.getUUID());
        player.removeAllEffects();
    }
    
    private boolean isNotAuth(Object entity) {
        if (entity instanceof ServerPlayer player) {
            return !DirectAuth.getLoginManager().isAuthenticated(player);
        }
        return false;
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
                        player.connection.disconnect(Component.literal(DirectAuth.getConfig().msgTimeout));
                        return; // Salimos para no ejecutar el resto de lógica
                    }
                }

                UUID uuid = player.getUUID();
                
                if (!anchorPositions.containsKey(uuid)) {
                    anchorPositions.put(uuid, player.position());
                }
                
                // Efectos de restricción
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 255, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.JUMP, 60, 250, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 1, false, false));
                
                // Forzar nivel de comida para evitar intentos de regeneración del motor del juego
                // (Aunque LivingHealEvent ya lo bloquea, esto evita la animación de temblor de hambre o saturación)
                if (player.getFoodData().getFoodLevel() > 0) {
                     // Opcional: Mantener la comida estática o dejarla como está. 
                     // Con LivingHealEvent es suficiente.
                }

                Vec3 anchor = anchorPositions.get(uuid);
                if (player.position().distanceToSqr(anchor) > 2.25) {
                    player.teleportTo(anchor.x, anchor.y, anchor.z);
                    player.setDeltaMovement(0, 0, 0);
                }
                
                // Removed player.clearFire() as fire ticks will be restored on authentication.
                
                if (player.tickCount % 100 == 0) {
                    player.displayClientMessage(
                        Component.literal(DirectAuth.getConfig().msgAuthReminder),
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
    public void onMount(EntityMountEvent event) {
        if (event.isMounting() && isNotAuth(event.getEntityMounting())) event.setCanceled(true);
    }
    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (isNotAuth(event.getPlayer())) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer player) {
                player.getInventory().add(event.getEntity().getItem());
                player.inventoryMenu.sendAllDataToRemote();
                player.containerMenu.broadcastChanges();
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgNoDrop));
            }
        }
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
        if (isNotAuth(event.getPlayer())) {
            String msg = event.getRawText();
            if (!msg.startsWith("/register") && !msg.startsWith("/login") && !msg.startsWith("/online")) {
                event.setCanceled(true);
                event.getPlayer().sendSystemMessage(Component.literal(DirectAuth.getConfig().msgUseCommands));
            }
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
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgUseCommands));
                }
            }
        }
    }
}