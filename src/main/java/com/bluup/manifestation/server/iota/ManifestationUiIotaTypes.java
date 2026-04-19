package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.HexUtils;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import com.bluup.manifestation.Manifestation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

public final class ManifestationUiIotaTypes {
    private ManifestationUiIotaTypes() {
    }

    public static final IotaType<UiButtonIota> UI_BUTTON = new IotaType<>() {
        @Nullable
        @Override
        public UiButtonIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            var actionsTag = HexUtils.downcast(ctag.get("actions"), ListTag.TYPE);

            Iota label = IotaType.deserialize(labelTag, world);
            var actions = new ArrayList<Iota>(actionsTag.size());
            for (Tag actionTag : actionsTag) {
                var cAction = HexUtils.downcast(actionTag, CompoundTag.TYPE);
                actions.add(IotaType.deserialize(cAction, world));
            }
            return new UiButtonIota(label, actions);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            return Component.literal("ui_button(")
                .append(IotaType.getDisplay(labelTag))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.GOLD);
        }

        @Override
        public int color() {
            return 0xff_d6a500;
        }
    };

    public static final IotaType<UiInputIota> UI_INPUT = new IotaType<>() {
        @Nullable
        @Override
        public UiInputIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            Iota label = IotaType.deserialize(labelTag, world);
            return new UiInputIota(label);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            return Component.literal("ui_input(")
                .append(IotaType.getDisplay(labelTag))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.YELLOW);
        }

        @Override
        public int color() {
            return 0xff_cca64f;
        }
    };

    public static final IotaType<UiSliderIota> UI_SLIDER = new IotaType<>() {
        @Nullable
        @Override
        public UiSliderIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            Iota label = IotaType.deserialize(labelTag, world);
            double min = DoubleIota.deserialize(ctag.get("min")).getDouble();
            double max = DoubleIota.deserialize(ctag.get("max")).getDouble();
            boolean hasCurrent = ctag.getBoolean("has_current");
            Double current = hasCurrent ? DoubleIota.deserialize(ctag.get("current")).getDouble() : null;
            return new UiSliderIota(label, min, max, current);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            return Component.literal("ui_slider(")
                .append(IotaType.getDisplay(labelTag))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.AQUA);
        }

        @Override
        public int color() {
            return 0xff_4fc6d8;
        }
    };

    public static final IotaType<UiSectionIota> UI_SECTION = new IotaType<>() {
        @Nullable
        @Override
        public UiSectionIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            Iota label = IotaType.deserialize(labelTag, world);
            return new UiSectionIota(label);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            return Component.literal("ui_section(")
                .append(IotaType.getDisplay(labelTag))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        }

        @Override
        public int color() {
            return 0xff_cf8cf2;
        }
    };

    public static final IotaType<UiDropdownIota> UI_DROPDOWN = new IotaType<>() {
        @Nullable
        @Override
        public UiDropdownIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            Iota label = IotaType.deserialize(labelTag, world);

            var optionsTag = HexUtils.downcast(ctag.get("options"), ListTag.TYPE);
            var options = new ArrayList<String>(optionsTag.size());
            for (Tag optionTag : optionsTag) {
                options.add(HexUtils.downcast(optionTag, StringTag.TYPE).getAsString());
            }

            int selected = HexUtils.downcast(ctag.get("selected"), IntTag.TYPE).getAsInt();
            return new UiDropdownIota(label, options, selected);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            return Component.literal("ui_dropdown(")
                .append(IotaType.getDisplay(labelTag))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.BLUE);
        }

        @Override
        public int color() {
            return 0xff_69a8ff;
        }
    };

    public static void register() {
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("ui_button"), UI_BUTTON);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("ui_input"), UI_INPUT);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("ui_slider"), UI_SLIDER);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("ui_section"), UI_SECTION);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("ui_dropdown"), UI_DROPDOWN);
    }
}