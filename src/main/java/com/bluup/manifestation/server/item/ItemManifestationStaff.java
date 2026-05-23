package com.bluup.manifestation.server.item;

import at.petrak.hexcasting.common.items.ItemStaff;
import at.petrak.hexcasting.common.lib.HexAttributes;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Base Manifestation staff that enlarges spellcasting grid scale.
 */
public class ItemManifestationStaff extends ItemStaff {
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

    protected static final AttributeModifier MEDIA_COST_REDUCTION = new AttributeModifier(
        UUID.fromString("54fa889d-ac03-4e44-a3ee-2068efa1f44d"),
        "Manifestation Staff Media Reduction",
        -0.5,
        AttributeModifier.Operation.MULTIPLY_TOTAL
    );

    public ItemManifestationStaff(Properties properties) {
        super(properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        var out = HashMultimap.create(super.getDefaultAttributeModifiers(slot));
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            out.put(HexAttributes.GRID_ZOOM, GRID_ZOOM_BONUS);
            out.put(HexAttributes.AMBIT_RADIUS, AMBIT_BONUS);
            out.put(HexAttributes.MEDIA_CONSUMPTION_MODIFIER, MEDIA_COST_REDUCTION);
        }
        return out;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.manifestation.staff.grid_zoom").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.manifestation.staff.empowered_ambit").withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("tooltip.manifestation.staff.empowered_media").withStyle(ChatFormatting.GREEN));
    }
}
