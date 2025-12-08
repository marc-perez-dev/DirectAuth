package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerRestrictionHandler {
    
    // Almacena la posición donde el jugador debe permanecer anclado
    private static final Map<UUID, Vec3> anchorPositions = new ConcurrentHashMap<>();

    // Limpiar ancla cuando el jugador se desconecta o se autentica
    public static void removeAnchor(ServerPlayer player) {
        anchorPositions.remove(player.getUUID());
    }
    
    private boolean isNotAuth(Object entity) {
        if (entity instanceof ServerPlayer player) {
            return !DirectAuth.getLoginManager().isAuthenticated(player);
        }
        return false;
    }

    // --- 1. Movimiento y Tick ---
    @SubscribeEvent
    public void onEntityTick(EntityTickEvent.Post event) {
        if (isNotAuth(event.getEntity())) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            UUID uuid = player.getUUID();
            
            // Lógica de Ancla
            if (!anchorPositions.containsKey(uuid)) {
                anchorPositions.put(uuid, player.position());
            }
            Vec3 anchor = anchorPositions.get(uuid);
            
            if (player.position().distanceToSqr(anchor) > 0.25) {
                player.teleportTo(anchor.x, anchor.y, anchor.z);
                player.setDeltaMovement(0, 0, 0);
            }
            
            // Extinguir fuego si se quema mientras se loguea
            if (player.isOnFire()) {
                player.clearFire();
            }
            
            // Recordatorio periódico
            if (player.tickCount % 100 == 0) {
                player.displayClientMessage(
                    Component.literal(DirectAuth.getConfig().msgAuthReminder),
                    true 
                );
            }
        } else if (event.getEntity() instanceof ServerPlayer player) {
            // Limpieza si ya se autenticó
            if (anchorPositions.containsKey(player.getUUID())) {
                anchorPositions.remove(player.getUUID());
            }
        }
    }

    // --- 2. Interacciones con el Mundo ---
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (isNotAuth(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }
    
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }
    
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onMount(EntityMountEvent event) {
        // Bloquear intentar montar
        if (event.isMounting() && isNotAuth(event.getEntityMounting())) {
            event.setCanceled(true);
        }
    }

    // --- 3. Inventario y Objetos ---
    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (isNotAuth(event.getPlayer())) {
            event.setCanceled(true);
            
            if (event.getPlayer() instanceof ServerPlayer player) {
                // Recuperar el ítem que se intentó tirar
                net.minecraft.world.item.ItemStack stack = event.getEntity().getItem();
                
                // Devolverlo al inventario
                player.getInventory().add(stack);
                
                // Forzar actualización visual del inventario al cliente
                player.inventoryMenu.sendAllDataToRemote();
                player.containerMenu.broadcastChanges();
                
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().msgNoDrop));
            }
        }
    }
    
    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (isNotAuth(event.getPlayer())) {
            event.setCanPickup(TriState.FALSE);
        }
    }
    
    @SubscribeEvent
    public void onXpPickup(PlayerXpEvent.PickupXp event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    // --- 4. Combate y Salud ---
    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (isNotAuth(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Pre event) {
        // Invulnerabilidad TOTAL mientras no esté logueado
        if (isNotAuth(event.getEntity())) {
            event.setNewDamage(0); // Anular daño en lugar de cancelar evento
        }
    }

    // --- 5. Chat ---
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (isNotAuth(event.getPlayer())) {
            String msg = event.getRawText();
            if (!msg.startsWith("/register") && !msg.startsWith("/login") && !msg.startsWith("/premium")) {
                event.setCanceled(true);
                event.getPlayer().sendSystemMessage(Component.literal(DirectAuth.getConfig().msgUseCommands));
            }
        }
    }
}
