package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
import miyucomics.hexical.features.curios.curios.CompassCurio;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Compass has it's own charm cast sound logic, needs it's own mixin to override it.
 */
@Mixin(value = CompassCurio.class, remap = false)
public abstract class HexicalCompassCurioMixin {
    @Inject(
        method = "postCharmCast",
        at = @At("HEAD"),
        require = 0,
        remap = false
    )
    private void manifestation$handleCompassCastSound(
        ServerPlayer user,
        ItemStack item,
        InteractionHand hand,
        ServerLevel world,
        List<?> stack,
        CallbackInfo ci
    ) {
        CharmCastSoundOverrides.INSTANCE.handlePostCastSound(user, world, item);
    }
}
