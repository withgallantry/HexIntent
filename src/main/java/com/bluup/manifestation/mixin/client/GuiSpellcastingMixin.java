package com.bluup.manifestation.mixin.client;

import at.petrak.hexcasting.client.gui.GuiSpellcasting;
import at.petrak.hexcasting.api.mod.HexTags;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GuiSpellcasting.class, remap = false)
public abstract class GuiSpellcastingMixin {
    @Unique
    private static final String HEXICAL_CHARM_UTIL_CLASS = "miyucomics.hexical.features.charms.CharmUtilities";

    @Unique
    private static final boolean HEXICAL_INTEROP = FabricLoader.getInstance().isModLoaded("hexical");

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/tags/TagKey;)Z"
        ),
        require = 0
    )
    private boolean manifestation$allowCharmedItemsAsCastingFocus(ItemStack stack, TagKey<?> tag) {
        @SuppressWarnings("unchecked")
        boolean matchesOriginalCheck = stack.is((TagKey) tag);
        if (matchesOriginalCheck) {
            return true;
        }

        if (tag == HexTags.Items.STAVES && manifestation$isHexicalCharmed(stack)) {
            return true;
        }

        return false;
    }

    @Unique
    private static boolean manifestation$isHexicalCharmed(ItemStack stack) {
        if (!HEXICAL_INTEROP) {
            var tag = stack.getTag();
            return tag != null && tag.contains("charmed");
        }

        try {
            Class<?> utilClass = Class.forName(HEXICAL_CHARM_UTIL_CLASS);
            var method = utilClass.getMethod("isStackCharmed", ItemStack.class);
            return (method.invoke(null, stack) instanceof Boolean b) && b;
        } catch (Throwable ignored) {
            var tag = stack.getTag();
            return tag != null && tag.contains("charmed");
        }
    }
}
