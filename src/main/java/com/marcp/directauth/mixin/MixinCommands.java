package com.marcp.directauth.mixin;

import com.marcp.directauth.events.PlayerRestrictionHandler;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Filtro de comandos de la jaula (solo /register, /login, /online), donde NeoForge dispara
 * CommandEvent: al principio de performCommand, por donde pasan los comandos que escriben los
 * jugadores (firmados o no) y también los de consola y RCON (que no tienen entidad y no se filtran).
 */
@Mixin(Commands.class)
public abstract class MixinCommands {

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void directAuth$filterCommandWhileUnauthenticated(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        if (PlayerRestrictionHandler.onCommand(parseResults)) {
            ci.cancel();
        }
    }
}
