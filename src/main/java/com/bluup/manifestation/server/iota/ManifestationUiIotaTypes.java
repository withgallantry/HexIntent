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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;

public final class ManifestationUiIotaTypes {
    private ManifestationUiIotaTypes() {
    }

    private static MutableComponent displayWithQuotedLabel(String name, Tag labelTag, ChatFormatting color) {
        String labelText = IotaType.getDisplay(HexUtils.downcast(labelTag, CompoundTag.TYPE)).getString();
        return Component.literal(name + "(\"")
            .append(Component.literal(labelText).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("\"").withStyle(ChatFormatting.GRAY))
            .withStyle(color);
    }

    private static String formatDouble(double value) {
        long asLong = (long) value;
        if (Math.abs(value - asLong) < 0.0000001) {
            return Long.toString(asLong);
        }
        return Double.toString(value);
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
            return displayWithQuotedLabel("IntentButton", labelTag, ChatFormatting.GOLD)
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
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
            return displayWithQuotedLabel("IntentInput", labelTag, ChatFormatting.YELLOW)
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
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
            double min = DoubleIota.deserialize(ctag.get("min")).getDouble();
            double max = DoubleIota.deserialize(ctag.get("max")).getDouble();
            boolean hasCurrent = ctag.getBoolean("has_current");

            var out = displayWithQuotedLabel("IntentSlider", labelTag, ChatFormatting.AQUA)
                .append(Component.literal(", " + formatDouble(min) + ", " + formatDouble(max)).withStyle(ChatFormatting.GRAY));

            if (hasCurrent) {
                double current = DoubleIota.deserialize(ctag.get("current")).getDouble();
                out.append(Component.literal(", " + formatDouble(current)).withStyle(ChatFormatting.GRAY));
            }

            return out
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
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
            return displayWithQuotedLabel("IntentSection", labelTag, ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
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
            var options = new ArrayList<Iota>(optionsTag.size());
            for (Tag optionTag : optionsTag) {
                options.add(IotaType.deserialize(HexUtils.downcast(optionTag, CompoundTag.TYPE), world));
            }

            int selected = HexUtils.downcast(ctag.get("selected"), IntTag.TYPE).getAsInt();
            return new UiDropdownIota(label, options, selected);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = HexUtils.downcast(ctag.get("label"), CompoundTag.TYPE);
            var optionsTag = HexUtils.downcast(ctag.get("options"), ListTag.TYPE);
            int selected = HexUtils.downcast(ctag.get("selected"), IntTag.TYPE).getAsInt();
            return displayWithQuotedLabel("IntentDropdown", labelTag, ChatFormatting.BLUE)
                .append(Component.literal(", options=" + optionsTag.size() + ", selected=" + selected).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public int color() {
            return 0xff_69a8ff;
        }
    };

    public static final IotaType<PresenceIntentIota> PRESENCE_INTENT = new IotaType<>() {
        @Nullable
        @Override
        public PresenceIntentIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            Vec3 position = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(ctag.get("position")).getVec3();
            Vec3 facing = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(ctag.get("facing")).getVec3();
            String dimension = ctag.contains("dimension")
                ? ctag.getString("dimension")
                : world.dimension().location().toString();
            return new PresenceIntentIota(position, facing, dimension);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            Vec3 position = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(ctag.get("position")).getVec3();
            return Component.literal("PresenceIntent(")
                .append(at.petrak.hexcasting.api.casting.iota.Vec3Iota.display(position))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.GREEN);
        }

        @Override
        public int color() {
            return 0xff_4fd875;
        }
    };

    public static void register() {
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_button"), UI_BUTTON);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_input"), UI_INPUT);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_slider"), UI_SLIDER);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_section"), UI_SECTION);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_dropdown"), UI_DROPDOWN);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("presence_intent"), PRESENCE_INTENT);
    }
}