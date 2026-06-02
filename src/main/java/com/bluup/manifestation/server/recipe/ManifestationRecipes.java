package com.bluup.manifestation.server.recipe;

import com.bluup.manifestation.Manifestation;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

public final class ManifestationRecipes {
    public static final RecipeSerializer<MemoryCrystalImprintRecipe> MEMORY_CRYSTAL_IMPRINT = Registry.register(
        BuiltInRegistries.RECIPE_SERIALIZER,
        Manifestation.id("memory_crystal_imprint"),
        new SimpleCraftingRecipeSerializer<>(MemoryCrystalImprintRecipe::new)
    );

    public static void register() {
    }

    private ManifestationRecipes() {
    }
}
