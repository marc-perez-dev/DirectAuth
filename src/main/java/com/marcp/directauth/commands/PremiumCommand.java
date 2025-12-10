package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.LoginManager;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.data.MigrationManager; // Importamos el nuevo Manager
import com.marcp.directauth.data.UserData;
import com.marcp.directauth.mixin.PlayerListAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PremiumCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("online")
            .executes(context -> execute(context, null))
            .then(Commands.argument("password", StringArgumentType.string())
                .executes(context -> execute(context, StringArgumentType.getString(context, "password")))
            )
        );
    }
    
    private static int execute(CommandContext<CommandSourceStack> context, String password) {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }
        
        if (!DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errNotAuthenticated));
            return 0;
        }
        
        String username = player.getGameProfile().getName();
        UserData userData = DirectAuth.getDatabase().getUser(username);
        
        if (userData == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errUserNotFound));
            return 0;
        }
        
        if (userData.isPremium()) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgAlreadyPremium));
            return 0;
        }

        if (password == null) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgOnlineModeWarning));
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumWarning));
            return 1;
        }

        if (!LoginManager.checkPassword(password, userData.getPasswordHash())) {
             player.sendSystemMessage(Component.literal(String.format(
                DirectAuth.getConfig().getLang().errWrongPassword, 
                1, 
                DirectAuth.getConfig().maxLoginAttempts
            )));
             return 0;
        }
        
        player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgVerifying));
        
        MojangAPI.getOnlineUUID(username).thenAccept(uuid -> {
            context.getSource().getServer().execute(() -> {
                if (uuid == null) {
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errMojangNotFound));
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgMojangHint));
                } else {
                    String formattedUUID = MojangAPI.formatUUID(uuid);
                    
                    // --- MIGRACIÓN DE DATOS (Delegada al Manager) ---
                    // Guardar datos actuales del jugador antes de migrar
                    ((PlayerListAccessor) player.getServer().getPlayerList()).callSave(player);

                    boolean migrationSuccess = MigrationManager.migratePlayerData(player, formattedUUID);
                    
                    if (!migrationSuccess) {
                        DirectAuth.LOGGER.error("Error migrando datos para {}", username);
                    }

                    // Actualizar DB
                    userData.setPremium(true);
                    userData.setOnlineUUID(formattedUUID);
                    DirectAuth.getDatabase().updateUser(username, userData);
                    
                    player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumSuccess));
                    
                    player.connection.disconnect(Component.literal(
                        DirectAuth.getConfig().getLang().msgPremiumKick
                    ));
                }
            });
        });
        
        return 1;
    }
}