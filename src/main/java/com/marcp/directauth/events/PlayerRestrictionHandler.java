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
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerRestrictionHandler {
    
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
        // El vehículo en el que salió, apartado mientras estaba sin autenticar (con la posición ya
        // restaurada, como en PlayerList.placeNewPlayer)
        JailedVehicles.restore(player);
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

    private static boolean isNotAuth(Object entity) {
        return isRestricted(entity);
    }

    private PlayerRestrictionHandler() {
    }

    /**
     * Las restricciones con callback de Fabric API se registran aquí. Las que no tienen (curarse,
     * recoger objetos y experiencia, montar, atacar, comandos, daño) las aplican mixins con
     * isRestricted (o los métodos on* de esta clase), en el mismo punto en el que NeoForge dispara
     * sus eventos.
     */
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PlayerRestrictionHandler::onServerTick);

        // Romper / clic izquierdo (Fabric reenvía el bloque al cliente al cancelar)
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !isNotAuth(player));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
            isNotAuth(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Clic derecho en bloque y con objeto (vanilla reenvía los bloques tras useItemOn)
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) ->
            isNotAuth(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
            isNotAuth(player) ? InteractionResultHolder.fail(player.getItemInHand(hand)) : InteractionResultHolder.pass(player.getItemInHand(hand)));
        // Interactuar con entidades: INTERACT y también INTERACT_AT (hitResult != null), que usan
        // p. ej. los armor stands para cambiar equipo
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
            isNotAuth(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // Respaldo: el chat de un jugador en la jaula ya se descarta al llegar (JailPackets.dropChat).
        // Los comandos no pasan por aquí (ver onCommand), así que todo el chat se bloquea
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (isNotAuth(sender)) {
                sender.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgUseCommands));
                return false;
            }
            return true;
        });
    }

    // --- 1. Movimiento y Tick ---
    // No se usa el tick del jugador: ServerPlayer.doTick() corre dentro del tick de la conexión,
    // que después devuelve al jugador a su posición de inicio de tick y desharía el teleport al
    // ancla. Al final del tick del servidor se recorren los jugadores conectados, fuera de él.
    private static void onServerTick(MinecraftServer server) {
        // Lo que hay que hacer con los jugadores en la jaula (expulsar, teleportar) puede cambiar la
        // lista de jugadores: se recorre la lista viva y solo se copian ellos, normalmente ninguno.
        List<ServerPlayer> jailed = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Los muertos que el nivel ya sacó (ver tickJailedPlayer) también: si no, nadie
            // expulsaría a quien se queda en la pantalla de muerte de una cuenta ajena
            if (isNotAuth(player)) {
                if (jailed == null) jailed = new ArrayList<>();
                jailed.add(player);
            } else if (!anchorPositions.isEmpty()) {
                anchorPositions.remove(player.getUUID());
            }
        }
        if (jailed != null) {
            for (ServerPlayer player : jailed) tickJailedPlayer(server, player);
        }
    }

    private static void tickJailedPlayer(MinecraftServer server, ServerPlayer player) {
        if (player.hasDisconnected()) return; // expulsado en este mismo tick
        // Con el tick del servidor: el de un jugador muerto deja de avanzar al salir del nivel tras
        // la animación de muerte, y aun así tiene que expulsarse
        int ticks = server.getTickCount();
        // Verificar Timeout (Kick) cada 20 ticks (1 segundo) para no saturar
        if (ticks % 20 == 0) {
            if (DirectAuth.getLoginManager().hasTimedOut(player)) {
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgTimeout));
                return; // Salimos para no ejecutar el resto de lógica
            }
        }
        if (ticks % 100 == 0) sendReminder(player);

        if (player.isRemoved()) {
            // Muerto y fuera del nivel: no se mueve, solo se ancla si aún no lo estaba. Como
            // EntityTickEvent.Post en main, que aún se dispara en el tick en el que el nivel lo
            // saca: un jugador que entra muerto sale en su primer tick, antes de llegar aquí. Sin
            // ancla, al reaparecer se anclaría donde lo deje vanilla (al azar alrededor del spawn)
            // y no en el spawn al que lo llevó ConnectionHandler.
            anchorPositions.computeIfAbsent(player.getUUID(), id -> new Anchor(player.level().dimension(), player.position()));
            return;
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
    }

    private static void sendReminder(ServerPlayer player) {
        player.displayClientMessage(Component.literal(DirectAuth.getConfig().getLang().msgAuthReminder), true);
    }

    /**
     * Daño ya reducido por armadura y efectos, antes de gastar los corazones de absorción
     * (Player.actuallyHurt, MixinPlayerDamage): como LivingDamageEvent.Pre de NeoForge.
     */
    public static float onDamage(Object entity, float damage) {
        return isNotAuth(entity) ? 0 : damage;
    }

    /**
     * Commands.performCommand (MixinCommands), por donde pasan los comandos que escriben los
     * jugadores, como CommandEvent de NeoForge. true = se cancela.
     */
    public static boolean onCommand(ParseResults<CommandSourceStack> parseResults) {
        if (parseResults.getContext().getSource().getEntity() instanceof ServerPlayer player) {
            if (isNotAuth(player)) {
                String input = parseResults.getReader().getString();
                String[] parts = input.trim().split(" ");
                String cmd = parts.length > 0 ? parts[0] : "";
                
                if (!cmd.equalsIgnoreCase("register") && 
                    !cmd.equalsIgnoreCase("login") && 
                    !cmd.equalsIgnoreCase("online")) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgUseCommands));
                    return true;
                }
            }
        }
        return false;
    }
}
