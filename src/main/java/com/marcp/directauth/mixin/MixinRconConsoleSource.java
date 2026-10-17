package com.marcp.directauth.mixin;

import com.marcp.directauth.auth.AuthTasks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.rcon.RconConsoleSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Los comandos de admin de DirectAuth terminan de forma asíncrona (DB, PBKDF2, Mojang), pero
 * vanilla lee la respuesta de RCON en cuanto el comando retorna, así que llegaba vacía. Antes de
 * leerla, el hilo de RCON espera a las operaciones del comando (ver AuthTasks); el hilo del
 * servidor sigue funcionando y es el que escribe el resultado en el búfer.
 */
@Mixin(RconConsoleSource.class)
public abstract class MixinRconConsoleSource {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "prepareForCommand", at = @At("HEAD"))
    private void directAuth$forgetPreviousOperations(CallbackInfo ci) {
        AuthTasks.resetRconOperations();
    }

    @Inject(method = "getCommandResponse", at = @At("HEAD"))
    private void directAuth$awaitPendingOperations(CallbackInfoReturnable<String> cir) {
        // Desde el hilo del servidor no se puede esperar: es el que completa las operaciones
        if (!this.server.isSameThread()) {
            AuthTasks.awaitRconOperations();
        }
    }
}
