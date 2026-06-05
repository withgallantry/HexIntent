package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
import miyucomics.hexical.features.charms.ServerCharmedUseReceiver;
import miyucomics.hexical.features.curios.CurioItem;
import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Curio specific minix to allow sound override
 */
@Mixin(value = ServerCharmedUseReceiver.class, remap = false)
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
        CurioItem curioItem,
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
        curioItem.postCharmCast(user, item, hand, world, castStack);
    }

    @Inject(
        method = "init$lambda$1$lambda$0",
        at = @At("TAIL"),
        remap = false,
        require = 0
    )
    private static void manifestation$handleNonCurioPostCastSound(
        int inputMethod,
        ServerPlayer user,
        InteractionHand hand,
        ItemStack item,
        CallbackInfo ci
    ) {
        if (item.getItem() instanceof CurioItem) {
            return;
        }

        CharmCastSoundOverrides.INSTANCE.handlePostCastSound(user, user.serverLevel(), item);
    }
}
