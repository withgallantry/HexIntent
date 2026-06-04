package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.CharmCastSoundOverrides;
import kotlin.Pair;
import miyucomics.hexical.features.charms.CharmUtilities;
import miyucomics.hexical.features.charms.ServerCharmedUseReceiver;
import miyucomics.hexical.features.curios.CurioItem;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, priority = 1100)
public abstract class HexicalMouseCastSoundMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true, require = 0)
    private void manifestation$handleConfiguredCharmSoundOverrides(
        long window,
        int button,
        int action,
        int mods,
        CallbackInfo ci
    ) {
        if (this.minecraft.screen != null || this.minecraft.getOverlay() != null) {
            return;
        }

        if (this.minecraft.player == null || this.minecraft.player.isSpectator()) {
            return;
        }

        if (action != GLFW.GLFW_PRESS) {
            return;
        }

        int buttonPressed = switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_1 -> 0;
            case GLFW.GLFW_MOUSE_BUTTON_2 -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_3 -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_4 -> 3;
            case GLFW.GLFW_MOUSE_BUTTON_5 -> 4;
            case GLFW.GLFW_MOUSE_BUTTON_6 -> 5;
            case GLFW.GLFW_MOUSE_BUTTON_7 -> 6;
            case GLFW.GLFW_MOUSE_BUTTON_8 -> 7;
            default -> -1;
        };

        if (buttonPressed == -1) {
            return;
        }

        for (Pair<InteractionHand, ItemStack> pair : CharmUtilities.getUseableCharmedItems(this.minecraft.player)) {
            ItemStack stack = pair.getSecond();
            if (!CharmUtilities.shouldIntercept(stack, buttonPressed, this.minecraft.player.isShiftKeyDown())) {
                continue;
            }

            if (stack.getItem() instanceof CurioItem) {
                return;
            }

            if (!CharmCastSoundOverrides.INSTANCE.hasConfiguredCastSoundOverride(stack)) {
                return;
            }

            this.minecraft.player.swing(pair.getFirst());

            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeInt(buttonPressed);
            buf.writeInt(pair.getFirst().ordinal());
            ClientPlayNetworking.send(ServerCharmedUseReceiver.CHARMED_ITEM_USE_CHANNEL, buf);

            ci.cancel();
            return;
        }
    }
}
