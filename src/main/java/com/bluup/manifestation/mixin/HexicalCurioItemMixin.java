package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
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
 * Optional compatibility hook for Hexical right-click charm casts on the concrete curio classes.
 */
@Mixin(targets = {
    "miyucomics.hexical.features.curios.CurioItem",
    "miyucomics.hexical.features.curios.curios.BaseCurio",
    "miyucomics.hexical.features.curios.curios.CompassCurio",
    "miyucomics.hexical.features.curios.curios.FluteCurio",
    "miyucomics.hexical.features.curios.curios.HandbellCurio",
    "miyucomics.hexical.features.curios.curios.StaffCurio"
})
public abstract class HexicalCurioItemMixin {
    @Inject(
        method = "postCharmCast",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void manifestation$applyCharmSoundOverride(
        ServerPlayer user,
        ItemStack item,
        InteractionHand hand,
        ServerLevel world,
        List<?> stack,
        CallbackInfo ci
    ) {
        if (CharmCastSoundOverrides.INSTANCE.handlePostCastSound(user, world, item)) {
            ci.cancel();
        }
    }
}