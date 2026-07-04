package com.bluup.manifestation.mixin;

import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import com.bluup.manifestation.server.CharmItemInterop;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiSpellcasting.class, remap = true)
public abstract class GuiSpellcastingOpenWithoutStaffMixin {
    @Shadow @Final private InteractionHand handOpenedWith;

    @Inject(
        method = {"tick", "method_25393"},
        at = @At(
            value = "INVOKE",
            target = "Lat/petrak/hexcasting/client/gui/GuiSpellcasting;closeForReal()V",
            shift = At.Shift.BEFORE
        ),
        cancellable = true
    )
    private void manifestation$keepOpenWhenOpenedWithoutStaff(CallbackInfo ci) {
        // don't strictly need it but my defensive programming side will not let me remove, it's cheap :P
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        var heldItem = player.getItemInHand(this.handOpenedWith);
        if (CharmItemInterop.isCharmedStack(heldItem)) {
            ci.cancel();
        }
    }
}
