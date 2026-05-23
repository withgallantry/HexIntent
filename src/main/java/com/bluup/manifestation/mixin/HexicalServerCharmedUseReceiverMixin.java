package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Universal compatibility hook for Hexical right-click charm casts.
 *
 * Intercepts the single invocation site where Hexical calls CurioItem.postCharmCast
 * and applies Manifestation per-item sound override/mute first.
 */
@Pseudo
@Mixin(targets = "miyucomics.hexical.features.charms.ServerCharmedUseReceiver")
public abstract class HexicalServerCharmedUseReceiverMixin {
    @Redirect(
        method = "lambda$init$0",
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

        invokePostCharmCast(curioItem, user, item, hand, world, stack);
    }

    private static void invokePostCharmCast(
        Object curioItem,
        ServerPlayer user,
        ItemStack item,
        InteractionHand hand,
        ServerLevel world,
        List<?> stack
    ) {
        try {
            Method target = null;
            for (Method method : curioItem.getClass().getMethods()) {
                if (method.getName().equals("postCharmCast") && method.getParameterCount() == 5) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                return;
            }

            target.invoke(curioItem, user, item, hand, world, stack);
        } catch (Throwable ignored) {
            // Keep compatibility hook non-fatal if Hexical internals differ.
        }
    }
}
