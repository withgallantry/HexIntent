package com.bluup.manifestation.server.item;

import at.petrak.hexcasting.common.items.ItemStaff;
import at.petrak.hexcasting.common.lib.HexAttributes;
import at.petrak.hexcasting.api.item.MediaHolderItem;
import at.petrak.hexcasting.api.misc.MediaConstants;
import at.petrak.hexcasting.api.utils.MediaHelper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;


public class ItemManifestationStaff extends ItemStaff implements MediaHolderItem {
    private static final long MAX_MEDIA = 400L * MediaConstants.DUST_UNIT;
    private static final long MEDIA_PER_RECHARGE = 5L * MediaConstants.DUST_UNIT;
    private static final int RECHARGE_INTERVAL_TICKS = 20 * 30;
    private static final String TAG_STORED_MEDIA = "ManifestationStaffStoredMedia";
    private static final String TAG_RECHARGE_ANCHOR_TICK = "ManifestationStaffRechargeAnchorTick";
    private static final String TAG_RECHARGE_ACTIVE = "ManifestationStaffRechargeActive";

    protected static final AttributeModifier GRID_ZOOM_BONUS = new AttributeModifier(
        UUID.fromString("fda8cbf8-b585-4be9-8ece-aa5ba95dc581"),
        "Manifestation Staff Grid Zoom",
        0.5,
        AttributeModifier.Operation.MULTIPLY_BASE
    );

    protected static final AttributeModifier AMBIT_BONUS = new AttributeModifier(
        UUID.fromString("1b5b45f1-3010-4f7e-bd7f-c45faa26d9a7"),
        "Manifestation Staff Ambit",
        4.0,
        AttributeModifier.Operation.ADDITION
    );

    public ItemManifestationStaff(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (level.isClientSide || !(entity instanceof net.minecraft.world.entity.player.Player player)) {
            return;
        }

        boolean inHotbar = slotId >= 0 && slotId < Inventory.getSelectionSize();
        boolean heldInMainOrOffhand = player.getMainHandItem() == stack || player.getOffhandItem() == stack;
        if (!inHotbar && !heldInMainOrOffhand) {
            return;
        }

        if (stack.getTag() == null || !stack.getTag().contains(TAG_STORED_MEDIA, Tag.TAG_ANY_NUMERIC)) {
            setMedia(stack, getMaxMedia(stack));
        }

        rechargeOneTick(stack, level.getGameTime());
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        var out = HashMultimap.create(super.getDefaultAttributeModifiers(slot));
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            out.put(HexAttributes.GRID_ZOOM, GRID_ZOOM_BONUS);
            out.put(HexAttributes.AMBIT_RADIUS, AMBIT_BONUS);
        }
        return out;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.manifestation.staff.grid_zoom").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.manifestation.staff.empowered_ambit").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("tooltip.manifestation.staff.battery_size", formatDust(getMedia(stack)), formatDust(getMaxMedia(stack))).withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("tooltip.manifestation.staff.battery_recharge", formatDust(MEDIA_PER_RECHARGE)).withStyle(ChatFormatting.GREEN));
    }


    @Override
    public long getMedia(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_STORED_MEDIA, Tag.TAG_ANY_NUMERIC)) {
            return getMaxMedia(stack);
        }
        return Math.max(0L, Math.min(tag.getLong(TAG_STORED_MEDIA), getMaxMedia(stack)));
    }

    @Override
    public long getMaxMedia(ItemStack stack) {
        return MAX_MEDIA;
    }

    @Override
    public void setMedia(ItemStack stack, long media) {
        CompoundTag tag = stack.getOrCreateTag();
        long max = getMaxMedia(stack);
        long clamped = Math.max(0L, Math.min(media, max));

        tag.putLong(TAG_STORED_MEDIA, clamped);

        if (clamped >= max) {
            tag.putBoolean(TAG_RECHARGE_ACTIVE, false);
        } else {
            tag.putBoolean(TAG_RECHARGE_ACTIVE, true);
        }
    }

    @Override
    public boolean canProvideMedia(ItemStack stack) {
        return getMaxMedia(stack) > 0;
    }

    @Override
    public boolean canRecharge(ItemStack stack) {
        return getMaxMedia(stack) > 0;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getMaxMedia(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return MediaHelper.mediaBarWidth(getMedia(stack), getMaxMedia(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return MediaHelper.mediaBarColor(getMedia(stack), getMaxMedia(stack));
    }

    private void rechargeOneTick(ItemStack stack, long gameTime) {
        long maxMedia = getMaxMedia(stack);
        if (maxMedia <= 0L) {
            return;
        }

        long currentMedia = getMedia(stack);
        CompoundTag tag = stack.getOrCreateTag();
        if (currentMedia >= maxMedia) {
            if (tag.getBoolean(TAG_RECHARGE_ACTIVE)) {
                tag.putBoolean(TAG_RECHARGE_ACTIVE, false);
            }
            return;
        }

        boolean rechargeActive = tag.getBoolean(TAG_RECHARGE_ACTIVE);
        if (!rechargeActive || !tag.contains(TAG_RECHARGE_ANCHOR_TICK, Tag.TAG_ANY_NUMERIC)) {
            tag.putBoolean(TAG_RECHARGE_ACTIVE, true);
            tag.putLong(TAG_RECHARGE_ANCHOR_TICK, gameTime);
            return;
        }

        long anchorTick = tag.getLong(TAG_RECHARGE_ANCHOR_TICK);
        if (gameTime <= anchorTick) {
            return;
        }

        long elapsedTicks = gameTime - anchorTick;
        long rechargeSteps = elapsedTicks / RECHARGE_INTERVAL_TICKS;
        if (rechargeSteps <= 0L) {
            return;
        }

        long restored = Math.min(maxMedia - currentMedia, rechargeSteps * MEDIA_PER_RECHARGE);
        if (restored > 0L) {
            setMedia(stack, currentMedia + restored);
            currentMedia += restored;
        }

        if (currentMedia >= maxMedia) {
            tag.putBoolean(TAG_RECHARGE_ACTIVE, false);
            tag.putLong(TAG_RECHARGE_ANCHOR_TICK, gameTime);
            return;
        }

        tag.putLong(TAG_RECHARGE_ANCHOR_TICK, anchorTick + (rechargeSteps * RECHARGE_INTERVAL_TICKS));
    }

    private static String formatDust(long media) {
        return String.valueOf(media / MediaConstants.DUST_UNIT);
    }
}
