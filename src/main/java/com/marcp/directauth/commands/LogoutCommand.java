package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class LogoutCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("logout")
            .executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                
                // 1. Desautenticar
                DirectAuth.getLoginManager().setAuthenticated(player, false);
                
                // 2. IMPORTANTE: Borrar cualquier sesión pendiente en el mapa graceSessions
                DirectAuth.getLoginManager().invalidateSession(player); 

                // 3. Kickear al jugador (opcional, pero es lo más seguro para que no se quede "bugueado" sin moverse)
                player.connection.disconnect(Component.literal(DirectAuth.getConfig().getLang().msgLogoutSuccess));
                
                return 1;
            })
        );
    }
}
