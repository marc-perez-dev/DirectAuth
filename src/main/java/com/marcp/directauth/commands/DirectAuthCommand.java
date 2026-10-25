package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.auth.AuthTasks;
import com.marcp.directauth.auth.ConfirmationManager;
import com.marcp.directauth.auth.MojangAPI;
import com.marcp.directauth.config.LangConfig;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class DirectAuthCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("directauth");

        // --- Subcomando: CONFIRM (Para todos los usuarios) ---
        root.then(Commands.literal("confirm")
            .executes(context -> {
                if (context.getSource().getEntity() instanceof ServerPlayer player) {
                    ConfirmationManager.confirm(player);
                    return 1;
                }
                return 0;
            })
        );

        // --- Subcomandos: ADMIN (Requieren OP nivel 4) ---

        // 1. Online Mode Toggle (Antiguo PremiumAdmin)
        root.then(Commands.literal("online")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsage))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(DirectAuthCommand::setOnlineMode)
                )
            )
        );

        // 2. Reset Password (Admin)
        root.then(Commands.literal("resetpass")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsageReset))
            .then(Commands.argument("user", StringArgumentType.string())
                .then(Commands.argument("newPassword", StringArgumentType.word())
                    .executes(DirectAuthCommand::resetPassword)
                )
            )
        );

        // 3. Force Unregister (Admin)
        root.then(Commands.literal("unregister")
            .requires(s -> s.hasPermission(4))
            .executes(ctx -> usage(ctx, DirectAuth.getConfig().getLang().errAdminUsageUnregister))
            .then(Commands.argument("user", StringArgumentType.string())
                .executes(DirectAuthCommand::forceUnregister)
            )
        );

        // 4. Reload config & language files from disk (Admin)
        root.then(Commands.literal("reload")
            .requires(s -> s.hasPermission(4))
            .executes(DirectAuthCommand::reloadConfig)
        );

        // 5. Reset language files to the mod's built-in defaults (Admin)
        root.then(Commands.literal("resetlang")
            .requires(s -> s.hasPermission(4))
            .executes(DirectAuthCommand::resetLang)
        );

        dispatcher.register(root);
    }

    /** Sends a localized usage hint when an admin subcommand is called without its arguments. */
    private static int usage(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        Path configPath = context.getSource().getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig").resolve("DirectAuth-config.json");
        if (!DirectAuth.initConfig(configPath)) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errConfigReloadFailed));
            return 0;
        }

        // Reaplicar el intervalo de limpieza de sesiones (init() es idempotente: reprograma la tarea)
        DirectAuth.getLoginManager().init(DirectAuth.getConfig().sessionCleanupInterval);

        context.getSource().sendSuccess(() -> Component.literal(
            DirectAuth.getConfig().getLang().msgConfigReloaded
        ), true);
        return 1;
    }

    private static int resetLang(CommandContext<CommandSourceStack> context) {
        Path serverConfig = context.getSource().getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("serverconfig");

        // Deleting the language files forces LangConfig.load() to regenerate them from the
        // mod's built-in defaults (this discards any manual customizations in those files).
        try {
            Files.deleteIfExists(serverConfig.resolve("DirectAuth-lang-en.json"));
            Files.deleteIfExists(serverConfig.resolve("DirectAuth-lang-es.json"));
        } catch (IOException e) {
            DirectAuth.LOGGER.error("DirectAuth: Failed to delete language files: {}", e.getMessage());
        }

        if (!DirectAuth.initConfig(serverConfig.resolve("DirectAuth-config.json"))) {
            context.getSource().sendFailure(Component.literal(DirectAuth.getConfig().getLang().errConfigReloadFailed));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(
            DirectAuth.getConfig().getLang().msgLangReset
        ), true);
        return 1;
    }

    private static int setOnlineMode(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "user");
        boolean newValue = BoolArgumentType.getBool(context, "value");

        AuthTasks.finish(source, DirectAuth.getDatabase().getUserAsync(username), userData -> {
            if (userData == null) {
                source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
                return;
            }
            if (newValue) {
                enableOnlineMode(source, username, userData);
            } else {
                disableOnlineMode(source, username, userData);
            }
        });
        return 1;
    }

    /** Resultado de consultar a Mojang: {@code onlineUUID} es null si la cuenta no existe. */
    private record MojangLookup(boolean reachable, String onlineUUID) {}

    private static void reportModeUpdated(CommandSourceStack source, String username, boolean value) {
        source.sendSuccess(() -> Component.literal(
            LangConfig.format(DirectAuth.getConfig().getLang().msgAdminPremiumUpdated, username, value)
        ), true);
    }

    /**
     * Jugador conectado Y autenticado con esta cuenta (cualquier variante de mayúsculas). Un
     * jugador encerrado sin login, o un suplantador con otra variante del nombre, no cuenta: su UUID
     * no es la de los datos de la cuenta.
     */
    private static ServerPlayer authenticatedPlayerOf(CommandSourceStack source, String username) {
        for (ServerPlayer player : AuthTasks.playersOfAccount(source.getServer(), username)) {
            if (DirectAuth.getLoginManager().isAuthenticated(player)) return player;
        }
        return null;
    }

    /**
     * Activar el Modo Online requiere la UUID de Mojang y saber con qué UUID juega la cuenta, que
     * depende de las mayúsculas exactas de su nombre: el jugador tiene que estar conectado y
     * autenticado (salvo que la cuenta ya la tenga guardada).
     */
    private static void enableOnlineMode(CommandSourceStack source, String username, UserData userData) {
        if (userData.isPremium() && userData.getOnlineUUID() != null) {
            reportModeUpdated(source, username, true); // Ya estaba activado
            return;
        }
        if (userData.getPlayerUUID() == null && authenticatedPlayerOf(source, username) == null) {
            source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminEnableNeedsPlayer, username)));
            return;
        }

        CompletableFuture<MojangLookup> lookup = MojangAPI.getOnlineUUID(username).handle((uuid, error) -> {
            if (error != null) {
                DirectAuth.LOGGER.warn("DirectAuth: Error querying Mojang API for {}: {}", username, error.getMessage());
                return new MojangLookup(false, null);
            }
            return new MojangLookup(true, MojangAPI.formatUUID(uuid));
        });

        AuthTasks.finish(source, lookup, result -> {
            if (!result.reachable()) {
                source.sendFailure(Component.literal(DirectAuth.getConfig().getLang().errMojangUnavailable));
                return;
            }
            if (result.onlineUUID() == null) {
                source.sendFailure(Component.literal(DirectAuth.getConfig().getLang().errMojangNotFound));
                return;
            }
            String playerUUID = userData.getPlayerUUID();
            if (playerUUID == null) {
                ServerPlayer target = authenticatedPlayerOf(source, username);
                if (target == null) { // Se desconectó mientras consultábamos a Mojang
                    source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminEnableNeedsPlayer, username)));
                    return;
                }
                playerUUID = target.getStringUUID();
            }
            saveOnlineMode(source, username, true, result.onlineUUID(), playerUUID);
        });
    }

    /**
     * No hace falta que esté conectado: la cuenta sigue jugando con la misma UUID. Las que activaron
     * el Modo Online antes de 1.3.1 tienen los datos bajo la UUID de Mojang, y se quedan con ella.
     */
    private static void disableOnlineMode(CommandSourceStack source, String username, UserData userData) {
        if (!userData.isPremium()) {
            reportModeUpdated(source, username, false); // Ya estaba desactivado
            return;
        }
        String playerUUID = userData.getPlayerUUID() != null ? userData.getPlayerUUID() : userData.getOnlineUUID();
        saveOnlineMode(source, username, false, null, playerUUID);
    }

    private static void saveOnlineMode(CommandSourceStack source, String username, boolean premium, String onlineUUID, String playerUUID) {
        AuthTasks.finish(source, DirectAuth.getDatabase().setOnlineModeAsync(username, premium, onlineUUID, playerUUID), updated -> {
            if (!updated) { // Borrada mientras tanto
                source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
                return;
            }
            reportModeUpdated(source, username, premium);
        });
    }

    private static int resetPassword(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "user");
        String newPass = StringArgumentType.getString(context, "newPassword");

        CompletableFuture<Boolean> updated = DirectAuth.getLoginManager().hashPasswordAsync(newPass)
            .thenCompose(hash -> DirectAuth.getDatabase().updatePasswordAsync(username, hash));

        AuthTasks.finish(source, updated, exists -> {
            if (!exists) {
                source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
                return;
            }

            // La contraseña anterior deja de dar acceso: sin sesiones de gracia guardadas y
            // todas las conexiones de la cuenta fuera (tendrán que entrar con la nueva).
            // También se levanta cualquier bloqueo por intentos fallidos.
            AuthTasks.revokeAccountAccess(source.getServer(), username, null, DirectAuth.getConfig().getLang().msgAdminPasswordResetKick);
            DirectAuth.getLoginManager().clearFailedLogins(username);

            source.sendSuccess(() -> Component.literal(
                LangConfig.format(DirectAuth.getConfig().getLang().msgAdminResetSuccess, username)
            ), true);
        });
        return 1;
    }

    private static int forceUnregister(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String username = StringArgumentType.getString(context, "user");

        AuthTasks.finish(source, DirectAuth.getDatabase().deleteUserAsync(username), deleted -> {
            if (!deleted) {
                source.sendFailure(Component.literal(LangConfig.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
                return;
            }

            // Sin sesiones de gracia que restauren una cuenta que ya no existe, y fuera todas
            // las conexiones de la cuenta
            AuthTasks.revokeAccountAccess(source.getServer(), username, null, DirectAuth.getConfig().getLang().msgAccountDeleted);
            DirectAuth.getLoginManager().clearFailedLogins(username);

            source.sendSuccess(() -> Component.literal(
                LangConfig.format(DirectAuth.getConfig().getLang().msgAdminUnregisterSuccess, username)
            ), true);
        });
        return 1;
    }
}
