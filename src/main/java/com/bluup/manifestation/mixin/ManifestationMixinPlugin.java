package com.bluup.manifestation.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class ManifestationMixinPlugin implements IMixinConfigPlugin {
    private static final String HEXICAL_COMPASS_CURIO_MIXIN =
        "com.bluup.manifestation.mixin.HexicalCompassCurioMixin";
    private static final String HEXICAL_CURIO_CAST_SOUND_MIXIN =
        "com.bluup.manifestation.mixin.HexicalCurioCastSoundMixin";
    private static final String HEXICAL_MOUSE_CAST_SOUND_MIXIN =
        "com.bluup.manifestation.mixin.HexicalMouseCastSoundMixin";
    private static final String HEXICAL_SERVER_RECEIVER_MIXIN =
        "com.bluup.manifestation.mixin.HexicalServerCharmedUseReceiverMixin";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!HEXICAL_COMPASS_CURIO_MIXIN.equals(mixinClassName)
            && !HEXICAL_CURIO_CAST_SOUND_MIXIN.equals(mixinClassName)
            && !HEXICAL_MOUSE_CAST_SOUND_MIXIN.equals(mixinClassName)
            && !HEXICAL_SERVER_RECEIVER_MIXIN.equals(mixinClassName)) {
            return true;
        }

        return FabricLoader.getInstance().isModLoaded("hexical");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}