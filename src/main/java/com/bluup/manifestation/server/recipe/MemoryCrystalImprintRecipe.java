package com.bluup.manifestation.server.recipe;

import at.petrak.hexcasting.api.item.HexHolderItem;
import at.petrak.hexcasting.api.mod.HexTags;
import at.petrak.hexcasting.xplat.IXplatAbstractions;
import com.bluup.manifestation.server.CharmCastSoundOverrides;
import com.bluup.manifestation.server.item.ManifestationItems;
import com.bluup.manifestation.server.item.MemoryCrystalData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class MemoryCrystalImprintRecipe extends CustomRecipe {
    public MemoryCrystalImprintRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        ItemStack memoryCrystal = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.is(ManifestationItems.MEMORY_CRYSTAL)) {
                if (!memoryCrystal.isEmpty()) {
                    return false;
                }
                memoryCrystal = stack;
                continue;
            }

            if (!target.isEmpty()) {
                return false;
            }
            target = stack;
        }

        if (memoryCrystal.isEmpty() || target.isEmpty()) {
            return false;
        }

        if (target.getCount() != 1) {
            return false;
        }

        return isCastableTarget(target);
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess access) {
        ItemStack memoryCrystal = ItemStack.EMPTY;
        ItemStack target = ItemStack.EMPTY;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.is(ManifestationItems.MEMORY_CRYSTAL)) {
                memoryCrystal = stack;
            } else {
                target = stack;
            }
        }

        if (memoryCrystal.isEmpty() || target.isEmpty() || !isCastableTarget(target)) {
            return ItemStack.EMPTY;
        }

        ItemStack output = target.copy();
        output.setCount(1);

        MemoryCrystalData.ensureMemoryId(memoryCrystal);
        MemoryCrystalData.copyMemoryData(memoryCrystal, output);

        return output;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ManifestationRecipes.MEMORY_CRYSTAL_IMPRINT;
    }

    private static boolean isCastableTarget(ItemStack stack) {
        if (stack.isEmpty() || stack.is(ManifestationItems.MEMORY_CRYSTAL)) {
            return false;
        }

        if (stack.is(HexTags.Items.STAVES)) {
            return true;
        }

        if (stack.getItem() instanceof HexHolderItem) {
            return true;
        }

        if (CharmCastSoundOverrides.INSTANCE.isHexicalCharmedStack(stack)) {
            return true;
        }

        return IXplatAbstractions.INSTANCE.findDataHolder(stack) != null;
    }
}
