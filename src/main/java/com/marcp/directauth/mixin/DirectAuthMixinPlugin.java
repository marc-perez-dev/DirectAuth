package com.marcp.directauth.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Añade los mixins de compatibilidad solo si la mod está instalada. Con @Pseudo bastaría para
 * que no fallen, pero Fabric registra un WARN por cada clase objetivo que no encuentra.
 */
public class DirectAuthMixinPlugin implements IMixinConfigPlugin {

    @Override
    public List<String> getMixins() {
        return FabricLoader.getInstance().isModLoaded("itemphysic") ? List.of("MixinItemPhysicPackets") : List.of();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
