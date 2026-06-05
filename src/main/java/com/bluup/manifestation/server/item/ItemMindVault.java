package com.bluup.manifestation.server.item;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ItemMindVault extends BlockItem {
    private static final String TAG_LOCKED_PROFESSION = "locked_profession";
    private static final String TAG_LOCKED_LEVEL = "locked_level";
    private static final String TAG_SLOTS = "slots";
    private static final String TAG_SLOT_OCCUPIED = "occupied";

    public ItemMindVault(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        CompoundTag beTag = BlockItem.getBlockEntityData(stack);
        if (beTag == null) {
            return;
        }

        String professionId = beTag.contains(TAG_LOCKED_PROFESSION, Tag.TAG_STRING)
            ? beTag.getString(TAG_LOCKED_PROFESSION)
            : "";
        int villagerLevel = beTag.getInt(TAG_LOCKED_LEVEL);
        int occupiedCount = countOccupiedSlots(beTag);

        if (occupiedCount <= 0 || professionId.isBlank() || villagerLevel <= 0) {
            return;
        }

        tooltip.add(
            Component.translatable(
                "hexcasting.tooltip.manifestation.mind_vault.item_profile",
                levelName(villagerLevel),
                formatProfession(professionId)
            ).withStyle(ChatFormatting.AQUA)
        );
        tooltip.add(
            Component.translatable(
                "hexcasting.tooltip.manifestation.mind_vault.item_count",
                occupiedCount
            ).withStyle(ChatFormatting.GREEN)
        );
    }

    private static int countOccupiedSlots(CompoundTag beTag) {
        ListTag slots = beTag.getList(TAG_SLOTS, Tag.TAG_COMPOUND);
        int count = 0;
        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slot = slots.getCompound(i);
            if (slot.getBoolean(TAG_SLOT_OCCUPIED)) {
                count++;
            }
        }
        return count;
    }

    private static Component levelName(int level) {
        return switch (level) {
            case 1 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.novice");
            case 2 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.apprentice");
            case 3 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.journeyman");
            case 4 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.expert");
            case 5 -> Component.translatable("hexcasting.tooltip.manifestation.mind_vault.level.master");
            default -> Component.literal("Level " + level);
        };
    }

    private static String formatProfession(String professionId) {
        ResourceLocation id = ResourceLocation.tryParse(professionId);
        String path = id == null ? professionId : id.getPath();
        String[] words = path.split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.length() == 0 ? professionId : out.toString();
    }
}