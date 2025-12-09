package com.marcp.directauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    // Este método "invocador" puentea la llamada al método protegido real 'save'
    @Invoker("save")
    void callSave(ServerPlayer player);
}
