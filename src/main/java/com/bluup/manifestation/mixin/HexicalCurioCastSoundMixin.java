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
 * Curios that only perform cast side effects via sound can be cancelled safely
 * when Manifestation handles charm audio override/mute.
 */
@Mixin(
    targets = {
        "miyucomics.hexical.features.curios.curios.BaseCurio",
        "miyucomics.hexical.features.curios.curios.FluteCurio",
        "miyucomics.hexical.features.curios.curios.HandbellCurio",
        "miyucomics.hexical.features.curios.curios.StaffCurio"
    },
    remap = false
)
public abstract class HexicalCurioCastSoundMixin {
    @Inject(
        method = "postCharmCast",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void manifestation$overrideCurioCastSound(
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
