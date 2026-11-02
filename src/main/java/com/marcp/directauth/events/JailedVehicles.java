package com.marcp.directauth.events;

import com.marcp.directauth.DirectAuth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El vehículo en el que un jugador se desconectó ("RootVehicle" de su fichero) no vuelve al mundo
 * hasta que se autentica. Al entrar todos los jugadores están sin autenticar, así que se saca del
 * fichero al cargarlo (MixinPlayerList, antes de que PlayerList.placeNewPlayer lo vuelva a montar)
 * y se guarda aquí; cada vez que se guarda el jugador se vuelve a escribir (MixinServerPlayerSave),
 * de modo que el autoguardado, la desconexión o un cierre inesperado dejan su fichero intacto. Así
 * quien entra con su nombre sin contraseña no puede sacar su caballo o su barca con cofre al mundo,
 * en el sitio oculto donde salió. Al autenticarse (PlayerRestrictionHandler.release, con la
 * posición ya restaurada) se crea y se monta como lo hace placeNewPlayer.
 */
public final class JailedVehicles {

    private static final String ROOT_VEHICLE = "RootVehicle";

    private static final Map<UUID, CompoundTag> stashed = new ConcurrentHashMap<>();

    private JailedVehicles() {
    }

    /** PlayerList.load, al que solo llama placeNewPlayer: aparta el vehículo del fichero cargado. */
    public static void take(ServerPlayer player, CompoundTag playerTag) {
        if (playerTag != null && playerTag.contains(ROOT_VEHICLE, Tag.TAG_COMPOUND)) {
            stashed.put(player.getUUID(), playerTag.getCompound(ROOT_VEHICLE));
            playerTag.remove(ROOT_VEHICLE);
        }
    }

    /** Al final de ServerPlayer.addAdditionalSaveData: el fichero conserva el vehículo apartado. */
    public static void writeBack(ServerPlayer player, CompoundTag playerTag) {
        if (stashed.isEmpty() || playerTag.contains(ROOT_VEHICLE, Tag.TAG_COMPOUND)) return;
        CompoundTag rootVehicle = stashed.get(player.getUUID());
        if (rootVehicle != null) playerTag.put(ROOT_VEHICLE, rootVehicle.copy());
    }

    /** Al final de PlayerList.remove: ya se ha guardado con el vehículo. */
    public static void forget(ServerPlayer player) {
        stashed.remove(player.getUUID());
    }

    /** Al detenerse el servidor. */
    public static void clear() {
        stashed.clear();
    }

    /**
     * Recién autenticado: crea el vehículo y lo monta, como PlayerList.placeNewPlayer (en el nivel
     * del jugador; si ya hay una entidad con su UUID, addWithUUID falla y no se crea). Si está
     * muerto, el vehículo se queda donde estaba, como al morir montado.
     */
    public static void restore(ServerPlayer player) {
        CompoundTag rootVehicle = stashed.remove(player.getUUID());
        if (rootVehicle == null) return;
        ServerLevel level = player.serverLevel();
        Entity entity = EntityType.loadEntityRecursive(
            rootVehicle.getCompound("Entity"), level, e -> !level.addWithUUID(e) ? null : e);
        if (entity == null || !player.isAlive()) return;

        UUID attach = rootVehicle.hasUUID("Attach") ? rootVehicle.getUUID("Attach") : null;
        if (entity.getUUID().equals(attach)) {
            player.startRiding(entity, true);
        } else {
            for (Entity passenger : entity.getIndirectPassengers()) {
                if (passenger.getUUID().equals(attach)) {
                    player.startRiding(passenger, true);
                    break;
                }
            }
        }

        if (!player.isPassenger()) {
            DirectAuth.LOGGER.warn("Couldn't reattach entity to player");
            entity.discard();
            for (Entity passenger : entity.getIndirectPassengers()) {
                passenger.discard();
            }
        }
    }
}
