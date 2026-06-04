package com.bluup.manifestation.server.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class ItemMemoryCrystal extends Item {
    public ItemMemoryCrystal(Properties properties) {
        super(properties);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        if (!level.isClientSide) {
            MemoryCrystalData.ensureMemoryId(stack);
        }
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide) {
            MemoryCrystalData.ensureMemoryId(stack);
        }
    }

    @Override
    public ItemStack getDefaultInstance() {
        ItemStack stack = super.getDefaultInstance();
        MemoryCrystalData.ensureMemoryId(stack);
        return stack;
    }
}
