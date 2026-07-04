package com.bluup.manifestation.server.item;

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
 * Staff variant with expanded ambit and reduced media consumption.
 */
public class ItemEmpoweredStaff extends ItemManifestationStaff {
    private static final AttributeModifier AMBIT_BONUS = new AttributeModifier(
        UUID.fromString("1b5b45f1-3010-4f7e-bd7f-c45faa26d9a7"),
        "Manifestation Empowered Staff Ambit",
        4.0,
        AttributeModifier.Operation.ADDITION
    );

    public ItemEmpoweredStaff(Properties properties) {
        super(properties);
    }

    @Override
    public Multimap<Attribute, AttributeModifier> getDefaultAttributeModifiers(EquipmentSlot slot) {
        var out = HashMultimap.create(super.getDefaultAttributeModifiers(slot));
        if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
            out.put(HexAttributes.AMBIT_RADIUS, AMBIT_BONUS);
        }
        return out;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.manifestation.staff.empowered_ambit").withStyle(ChatFormatting.LIGHT_PURPLE));
    }
}
