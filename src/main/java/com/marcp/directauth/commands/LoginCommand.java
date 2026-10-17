package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.auth.LoginManager.PasswordCheck;
import com.marcp.directauth.config.LangConfig;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.marcp.directauth.mixin.ServerPlayerAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


public class LoginCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.greedyString())
                .executes(LoginCommand::execute)
            )
        );
    }
    
    /** Cuenta leída de la DB y resultado de comprobar la contraseña contra ella. */
    private record Attempt(UserData user, PasswordCheck check) {}

    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        LoginManager loginManager = DirectAuth.getLoginManager();
        
        // Verificar si ya está autenticado
        if (loginManager.isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyAuthenticated));
            return 0;
        }
        
        // Verificar si la cuenta está bloqueada por demasiados intentos (antes de gastar un hash)
        if (loginManager.hasExceededMaxAttempts(player)) {
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errMaxAttempts));
            return 0;
        }

        // Verificar cooldown
        if (!loginManager.canAttemptLogin(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errCooldown));
            return 0;
        }

        if (!AuthTasks.begin(player)) return 0;
        loginManager.markLoginAttempt(player);
        
        String username = player.getGameProfile().getName();
        String password = StringArgumentType.getString(context, "password");

        // DB y PBKDF2 fuera del hilo del servidor
        CompletableFuture<Attempt> attempt = DirectAuth.getDatabase().getUserAsync(username).thenCompose(user ->
            user == null
                ? CompletableFuture.completedFuture(new Attempt(null, PasswordCheck.WRONG))
                : loginManager.verifyPasswordAsync(password, user.getPasswordHash()).thenCompose(check ->
                    !check.matches()
                        ? CompletableFuture.completedFuture(new Attempt(user, check))
                        // El hash tarda ~100ms: si mientras tanto cambió la contraseña (resetpass,
                        // /changepassword), la comprobación ya no vale
                        : DirectAuth.getDatabase().getUserAsync(username).thenApply(current ->
                            current != null && user.getPasswordHash().equals(current.getPasswordHash())
                                ? new Attempt(user, check)
                                : new Attempt(current, PasswordCheck.WRONG))));

        AuthTasks.finish(player, attempt, (p, result) -> onAttempt(p, result, password));
        return 1;
    }

    private static void onAttempt(ServerPlayer player, Attempt attempt, String password) {
        LoginManager loginManager = DirectAuth.getLoginManager();

        // Verificar si está registrado
        if (attempt.user() == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotRegistered));
            return;
        }
        if (loginManager.isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyAuthenticated));
            return;
        }

        if (!attempt.check().matches()) {
            loginManager.recordLoginResult(player, false);
            int attempts = loginManager.getFailedAttempts(player);
            int maxAttempts = DirectAuth.getConfig().maxLoginAttempts;
            if (attempts >= maxAttempts) {
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errMaxAttempts));
            } else {
                player.sendSystemMessage(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errWrongPassword, attempts, maxAttempts)));
            }
            return;
        }

        loginManager.recordLoginResult(player, true);
        loginManager.setAuthenticated(player, true);

        // Hash con formato/iteraciones antiguos: regenerarlo ahora que conocemos la contraseña
        if (attempt.check() == PasswordCheck.OK_NEEDS_REHASH) {
            String oldHash = attempt.user().getPasswordHash();
            loginManager.hashPasswordAsync(password)
                .thenCompose(newHash -> DirectAuth.getDatabase().replacePasswordHashAsync(attempt.user().getUsername(), oldHash, newHash))
                .exceptionally(error -> {
                    DirectAuth.LOGGER.warn("DirectAuth: Could not upgrade the password hash of {}", attempt.user().getUsername(), error);
                    return false;
                });
        }

        // [CAMBIO CLAVE] Lógica de Retorno Inteligente
        boolean restored = DirectAuth.getPositionManager().restorePosition(player);
        
        if (!restored) {
            // Caso: Entró muerto o es nuevo -> Enviar a su punto de reaparición con las reglas de
            // vanilla (que ya aplican al estar autenticado): el nexo necesita carga y la gasta, y
            // una cama/nexo que ya no existe manda al spawn del mundo.
            // (Misma búsqueda que PlayerList.respawn en 1.20.1.)
            BlockPos respawnPos = player.getRespawnPosition();
            float respawnAngle = player.getRespawnAngle();
            ServerLevel respawnLevel = player.getServer().getLevel(player.getRespawnDimension());
            Optional<Vec3> respawn = respawnLevel != null && respawnPos != null
                ? Player.findRespawnPositionAndUseSpawnBlock(respawnLevel, respawnPos, respawnAngle, player.isRespawnForced(), false)
                : Optional.empty();
            if (respawn.isPresent()) {
                Vec3 pos = respawn.get();
                BlockState block = respawnLevel.getBlockState(respawnPos);
                float yRot = respawnAngle;
                if (block.is(BlockTags.BEDS) || block.is(Blocks.RESPAWN_ANCHOR)) {
                    // Mirando hacia la cama / nexo, como vanilla
                    Vec3 towards = Vec3.atBottomCenterOf(respawnPos).subtract(pos).normalize();
                    yRot = (float) Mth.wrapDegrees(Mth.atan2(towards.z, towards.x) * (180F / Math.PI) - 90.0D);
                }
                player.teleportTo(respawnLevel, pos.x, pos.y, pos.z, yRot, 0.0F);
            } else {
                if (respawnLevel != null && respawnPos != null) {
                    player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
                }
                // Spawn del mundo con la colocación de vanilla (radio de spawn, sin colisiones).
                // fudgeSpawnLocation solo mueve la entidad: primero se pasa al Overworld y al
                // final se teletransporta para sincronizar al cliente.
                ServerLevel overworld = player.getServer().overworld();
                if (player.level() != overworld) {
                    BlockPos spawnPos = overworld.getSharedSpawnPos();
                    player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
                }
                ((ServerPlayerAccessor) player).callFudgeSpawnLocation(overworld);
                player.teleportTo(overworld, player.getX(), player.getY(), player.getZ(), 0, 0);
            }
        }

        // Liberar ancla de restricción y resincronizar efectos
        PlayerRestrictionHandler.release(player);

        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAuthenticated));
    }
}
