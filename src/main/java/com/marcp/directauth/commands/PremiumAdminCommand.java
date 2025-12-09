package com.marcp.directauth.commands;

import com.marcp.directauth.DirectAuth;
import com.marcp.directauth.data.UserData;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class PremiumAdminCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("directauth")
            .then(Commands.literal("online")
                .requires(source -> source.hasPermission(4)) // OP Level 4
                .then(Commands.argument("user", StringArgumentType.string())
                    .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(PremiumAdminCommand::execute)
                    )
                )
            )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        String username = StringArgumentType.getString(context, "user");
        boolean newValue = BoolArgumentType.getBool(context, "value");
        CommandSourceStack source = context.getSource();

        UserData userData = DirectAuth.getDatabase().getUser(username);

        if (userData == null) {
            source.sendFailure(Component.literal(String.format(DirectAuth.getConfig().getLang().errAdminUserNotFound, username)));
            return 0;
        }

        userData.setPremium(newValue);
        DirectAuth.getDatabase().updateUser(username, userData);
        
        source.sendSuccess(() -> Component.literal(
            String.format(DirectAuth.getConfig().getLang().msgAdminPremiumUpdated, username, newValue)
        ), true);

        return 1;
    }
}