package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
import miyucomics.hexical.features.curios.CurioItem;
import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Mixing for charm cast sound override. Made it so it's optional, as I don't want to hard depend on Hexical just for this.
 *
 * Intercepts the single invocation site where Hexical calls CurioItem.postCharmCast
 * and applies Manifestation per-item sound override/mute first.
 */
@Mixin(targets = "miyucomics.hexical.features.charms.ServerCharmedUseReceiver")
public abstract class HexicalServerCharmedUseReceiverMixin {
    @Redirect(
        method = "init$lambda$1$lambda$0",
        at = @At(
            value = "INVOKE",
            target = "Lmiyucomics/hexical/features/curios/CurioItem;postCharmCast(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V"
        ),
        remap = false,
        require = 0
    )
    private static void manifestation$redirectCurioPostCast(
        Object curioItem,
        ServerPlayer user,
        ItemStack item,
        InteractionHand hand,
        ServerLevel world,
        List<?> stack
    ) {
        if (CharmCastSoundOverrides.INSTANCE.handlePostCastSound(user, world, item)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<? extends Iota> castStack = (List<? extends Iota>) stack;
        invokePostCharmCast(curioItem, user, item, hand, world, castStack);
    }

    private static void invokePostCharmCast(
        Object curioItem,
        ServerPlayer user,
        ItemStack item,
        InteractionHand hand,
        ServerLevel world,
        List<? extends Iota> stack
    ) {
        ((CurioItem) curioItem).postCharmCast(user, item, hand, world, stack);
    }
}
