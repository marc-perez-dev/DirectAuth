package com.marcp.directauth.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.config.LangConfig;
import com.marcp.directauth.data.DatabaseManager.RegistrationResult;
import com.marcp.directauth.events.PlayerRestrictionHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

public class RegisterCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("register")
            .then(Commands.argument("password", StringArgumentType.word())
                .executes(RegisterCommand::execute)
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        // En Brigadier, verificar la fuente es crucial
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errNotPlayer));
            return 0;
        }

        if (DirectAuth.getLoginManager().isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyAuthenticated));
            return 0;
        }

        String username = player.getGameProfile().getName();
        String playerIp = player.getIpAddress();

        // --- 1. CHECK ANTI-BOT: RETRASO TEMPORAL ---
        long joinTime = DirectAuth.getLoginManager().getConnectionTime(player);
        long secondsAlive = (System.currentTimeMillis() - joinTime) / 1000;
        int requiredDelay = DirectAuth.getConfig().registrationDelay;

        if (secondsAlive < requiredDelay) {
            player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errRegistrationCooldown));
            return 0;
        }

        String password = StringArgumentType.getString(context, "password");

        // Validar contraseña
        if (password.length() < DirectAuth.getConfig().minPasswordLength) {
            player.sendSystemMessage(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errPasswordTooShort, DirectAuth.getConfig().minPasswordLength)));
            return 0;
        }

        if (password.length() > DirectAuth.getConfig().maxPasswordLength) {
            player.sendSystemMessage(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errPasswordTooLong, DirectAuth.getConfig().maxPasswordLength)));
            return 0;
        }

        if (!AuthTasks.begin(player)) return 0;

        // --- 2. CHECK ANTI-BOT: LÍMITE DE IP + cuenta existente ---
        // Comprobación previa para no gastar un hash en registros que se van a rechazar, y
        // comprobación definitiva junto al INSERT (atómica en el hilo de la DB).
        int maxAccountsPerIP = DirectAuth.getConfig().maxAccountsPerIP;
        CompletableFuture<RegistrationResult> result = DirectAuth.getDatabase()
            .checkRegistrationAsync(username, playerIp, maxAccountsPerIP)
            .thenCompose(check -> check != RegistrationResult.OK
                ? CompletableFuture.completedFuture(check)
                : DirectAuth.getLoginManager().hashPasswordAsync(password)
                    .thenCompose(hash -> DirectAuth.getDatabase().registerUserAsync(username, hash, playerIp, maxAccountsPerIP)));

        AuthTasks.finish(player, result, RegisterCommand::onResult);
        return 1;
    }

    private static void onResult(ServerPlayer player, RegistrationResult result) {
        switch (result) {
            case ALREADY_REGISTERED ->
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errAlreadyRegistered));
            case IP_LIMIT_REACHED ->
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().errIpLimitReached));
            case OK -> {
                // Autenticar automáticamente (la cuenta ya está guardada)
                DirectAuth.getLoginManager().setAuthenticated(player, true);

                // Restaurar posición original si existe (por si se movió al spawn al entrar)
                DirectAuth.getPositionManager().restorePosition(player);
                // Liberar ancla de restricción y resincronizar efectos
                PlayerRestrictionHandler.release(player);

                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgRegistered));
                player.sendSystemMessage(Component.literal(DirectAuth.getConfig().getLang().msgPremiumEnableHint));
            }
        }
    }
}
