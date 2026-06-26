package com.bluup.manifestation.mixin;

import com.bluup.manifestation.server.item.ItemManifestationStaff;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererStaffRechargeMixin {
    private static final String TAG_STORED_MEDIA = "ManifestationStaffStoredMedia";
    private static final String TAG_RECHARGE_ANCHOR_TICK = "ManifestationStaffRechargeAnchorTick";
    private static final String TAG_RECHARGE_ACTIVE = "ManifestationStaffRechargeActive";

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"
        )
    )
    private static boolean manifestation$ignoreRechargeOnlyStaffNbtForSwapAnim(ItemStack previous, ItemStack current) {
        if (ItemStack.matches(previous, current)) {
            return true;
        }
        if (!(previous.getItem() instanceof ItemManifestationStaff)
            || !previous.is(current.getItem())
            || previous.getCount() != current.getCount()) {
            return false;
        }
        return normalize(previous.getTag()).equals(normalize(current.getTag()));
    }

    private static CompoundTag normalize(CompoundTag tag) {
        CompoundTag out = tag == null ? new CompoundTag() : tag.copy();
        out.remove(TAG_STORED_MEDIA);
        out.remove(TAG_RECHARGE_ACTIVE);
        out.remove(TAG_RECHARGE_ANCHOR_TICK);
        return out;
    }
}
