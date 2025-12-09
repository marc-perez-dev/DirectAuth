package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;
import net.minecraft.world.level.block.BedBlock; // Added import
import net.minecraft.world.level.block.RespawnAnchorBlock; // Added import
import net.minecraft.world.level.block.state.BlockState; // Added import
import net.minecraft.world.level.block.Block; // Added import
import net.minecraft.world.entity.EntityType; // Added import


public class LoginCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.greedyString())
                .executes(LoginCommand::execute)
            )
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        // Verificar si está registrado
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotRegistered));
            return 0;
        }
        
        // Verificar si ya está autenticado
        if (DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyAuthenticated));
            return 0;
        }
        
        // Verificar cooldown
        if (!DirectAuth.getLoginManager().canAttemptLogin(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errCooldown));
            return 0;
        }
        
        // Verificar si excedió intentos
        if (DirectAuth.getLoginManager().hasExceededMaxAttempts(player)) {
            player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().errMaxAttempts));
            return 0;
        }
        
        String password = StringArgumentType.getString(context, "password");
        
        // Verificar contraseña
        if (LoginManager.checkPassword(password, userData.getPasswordHash())) {
            DirectAuth.getLoginManager().setAuthenticated(player, true);
            DirectAuth.getLoginManager().recordLoginAttempt(player, true);
            
            // [CAMBIO CLAVE] Lógica de Retorno Inteligente
            boolean restored = DirectAuth.getPositionManager().restorePosition(player);
            
            if (!restored) {
                // Caso: Entró muerto o es nuevo -> Enviar a Cama/Nexo
                BlockPos respawnPos = player.getRespawnPosition();
                ResourceKey<Level> respawnDim = player.getRespawnDimension();

                if (respawnPos != null) {
                    ServerLevel level = player.getServer().getLevel(respawnDim);
                    if (level != null) {
                        // --- LÓGICA CORREGIDA PARA 1.21 ---
                        Optional<Vec3> safePos = Optional.empty();
                        
                        // Verificar si el bloque en la posición de respawn es válido
                        BlockState state = level.getBlockState(respawnPos);
                        Block block = state.getBlock();

                        if (block instanceof BedBlock) {
                            // Calcular posición segura para levantarse de la cama
                            safePos = BedBlock.findStandUpPosition(
                                EntityType.PLAYER, 
                                level, 
                                respawnPos, 
                                state.getValue(BedBlock.FACING), 
                                player.getRespawnAngle()
                            );
                        } else if (block instanceof RespawnAnchorBlock) {
                            // Calcular posición segura para el nexo de reaparición
                            safePos = RespawnAnchorBlock.findStandUpPosition(
                                EntityType.PLAYER, 
                                level, 
                                respawnPos
                            );
                        } else if (player.isRespawnForced()) {
                            // Si el respawn es forzado (comando) y no hay cama/nexo, usar la posición tal cual
                            safePos = Optional.of(Vec3.atBottomCenterOf(respawnPos));
                        }
                        // ------------------------------------

                        if (safePos.isPresent()) {
                            Vec3 pos = safePos.get();
                            player.teleportTo(level, pos.x, pos.y, pos.z, player.getRespawnAngle(), 0.0F);
                        }
                    }
                }
            }

            // Liberar ancla de restricción
            PlayerRestrictionHandler.removeAnchor(player);
            
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAuthenticated));
            return 1;
        } else {
            DirectAuth.getLoginManager().recordLoginAttempt(player, false);
            int attempts = DirectAuth.getLoginManager().getFailedAttempts(player);
            player.sendSystemMessage(Component.literal(String.format(DirectAuth.getConfig().getLang().errWrongPassword, attempts, DirectAuth.getConfig().maxLoginAttempts)));
            return 0;
        }
    }
}