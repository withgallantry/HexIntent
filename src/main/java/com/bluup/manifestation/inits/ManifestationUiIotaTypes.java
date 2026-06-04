package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.HexUtils;
import at.petrak.hexcasting.api.utils.NBTHelper;
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes;
import com.bluup.manifestation.Manifestation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
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
            var labelTag = NBTHelper.getCompound(ctag, "label");
            var actionsTag = NBTHelper.getList(ctag, "actions", Tag.TAG_COMPOUND);

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
            var labelTag = NBTHelper.getCompound(ctag, "label");
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
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);
            return new UiInputIota(label);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            return displayWithQuotedLabel("IntentInput", labelTag, ChatFormatting.YELLOW)
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public int color() {
            return 0xff_cca64f;
        }
    };

    public static final IotaType<UiNumericInputIota> UI_NUMERIC_INPUT = new IotaType<>() {
        @Nullable
        @Override
        public UiNumericInputIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);
            boolean hasCurrent = NBTHelper.getBoolean(ctag, "has_current");
            Double current = hasCurrent ? DoubleIota.deserialize(NBTHelper.get(ctag, "current")).getDouble() : null;
            return new UiNumericInputIota(label, current);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            boolean hasCurrent = NBTHelper.getBoolean(ctag, "has_current");

            var out = displayWithQuotedLabel("IntentNumericInput", labelTag, ChatFormatting.DARK_AQUA);
            if (hasCurrent) {
                double current = DoubleIota.deserialize(NBTHelper.get(ctag, "current")).getDouble();
                out.append(Component.literal(", " + formatDouble(current)).withStyle(ChatFormatting.GRAY));
            }

            return out
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public int color() {
            return 0xff_4fa7b5;
        }
    };

    public static final IotaType<UiCheckboxIota> UI_CHECKBOX = new IotaType<>() {
        @Nullable
        @Override
        public UiCheckboxIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);
            boolean checked = NBTHelper.getBoolean(ctag, "checked");
            return new UiCheckboxIota(label, checked);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            boolean checked = NBTHelper.getBoolean(ctag, "checked");
            return displayWithQuotedLabel("IntentCheckbox", labelTag, ChatFormatting.DARK_GREEN)
                .append(Component.literal(", " + checked).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public int color() {
            return 0xff_68ba5c;
        }
    };

    public static final IotaType<UiSelectListIota> UI_SELECT_LIST = new IotaType<>() {
        @Nullable
        @Override
        public UiSelectListIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);

            var optionsTag = NBTHelper.getList(ctag, "options", Tag.TAG_COMPOUND);
            var options = new ArrayList<Iota>(optionsTag.size());
            for (Tag optionTag : optionsTag) {
                options.add(IotaType.deserialize(NBTHelper.getAsCompound(optionTag), world));
            }

            int maxRows = NBTHelper.getInt(ctag, "max_rows");
            boolean multiSelect = NBTHelper.getBoolean(ctag, "multi_select");
            return new UiSelectListIota(label, options, maxRows, multiSelect);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            var optionsTag = NBTHelper.getList(ctag, "options", Tag.TAG_COMPOUND);
            int maxRows = NBTHelper.getInt(ctag, "max_rows");
            boolean multiSelect = NBTHelper.getBoolean(ctag, "multi_select");
            return displayWithQuotedLabel("IntentSelectList", labelTag, ChatFormatting.DARK_BLUE)
                .append(Component.literal(", options=" + optionsTag.size() + ", rows=" + maxRows + ", multi=" + multiSelect).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }

        @Override
        public int color() {
            return 0xff_4f77c8;
        }
    };

    public static final IotaType<UiSliderIota> UI_SLIDER = new IotaType<>() {
        @Nullable
        @Override
        public UiSliderIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);
            double min = DoubleIota.deserialize(NBTHelper.get(ctag, "min")).getDouble();
            double max = DoubleIota.deserialize(NBTHelper.get(ctag, "max")).getDouble();
            boolean hasCurrent = NBTHelper.getBoolean(ctag, "has_current");
            Double current = hasCurrent ? DoubleIota.deserialize(NBTHelper.get(ctag, "current")).getDouble() : null;
            return new UiSliderIota(label, min, max, current);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            double min = DoubleIota.deserialize(NBTHelper.get(ctag, "min")).getDouble();
            double max = DoubleIota.deserialize(NBTHelper.get(ctag, "max")).getDouble();
            boolean hasCurrent = NBTHelper.getBoolean(ctag, "has_current");

            var out = displayWithQuotedLabel("IntentSlider", labelTag, ChatFormatting.AQUA)
                .append(Component.literal(", " + formatDouble(min) + ", " + formatDouble(max)).withStyle(ChatFormatting.GRAY));

            if (hasCurrent) {
                double current = DoubleIota.deserialize(NBTHelper.get(ctag, "current")).getDouble();
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
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);
            return new UiSectionIota(label);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
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
            var labelTag = NBTHelper.getCompound(ctag, "label");
            Iota label = IotaType.deserialize(labelTag, world);

            var optionsTag = NBTHelper.getList(ctag, "options", Tag.TAG_COMPOUND);
            var options = new ArrayList<Iota>(optionsTag.size());
            for (Tag optionTag : optionsTag) {
                options.add(IotaType.deserialize(NBTHelper.getAsCompound(optionTag), world));
            }

            int selected = NBTHelper.getInt(ctag, "selected");
            return new UiDropdownIota(label, options, selected);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var labelTag = NBTHelper.getCompound(ctag, "label");
            var optionsTag = NBTHelper.getList(ctag, "options", Tag.TAG_COMPOUND);
            int selected = NBTHelper.getInt(ctag, "selected");
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
            Vec3 position = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(NBTHelper.get(ctag, "position")).getVec3();
            Vec3 facing = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(NBTHelper.get(ctag, "facing")).getVec3();
            String dimension = NBTHelper.hasString(ctag, "dimension")
                ? NBTHelper.getString(ctag, "dimension")
                : world.dimension().location().toString();
            return new PresenceIntentIota(position, facing, dimension);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            Vec3 position = at.petrak.hexcasting.api.casting.iota.Vec3Iota.deserialize(NBTHelper.get(ctag, "position")).getVec3();
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

    public static final IotaType<MemoryIota> MEMORY = new IotaType<>() {
        @Nullable
        @Override
        public MemoryIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var id = NBTHelper.getString(ctag, "id");
            return new MemoryIota(id);
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            var id = NBTHelper.getString(ctag, "id");
            return Component.literal("Memory(\"")
                .append(Component.literal(id).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.DARK_AQUA);
        }

        @Override
        public int color() {
            return 0xff_63c6d5;
        }
    };

    public static final IotaType<EquationParticleIota> EQUATION_PARTICLE = new IotaType<>() {
        @Nullable
        @Override
        public EquationParticleIota deserialize(Tag tag, ServerLevel world) throws IllegalArgumentException {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            String x = NBTHelper.getString(ctag, "x");
            String y = NBTHelper.getString(ctag, "y");
            String z = NBTHelper.getString(ctag, "z");
            double tMin = NBTHelper.getDouble(ctag, "t_min");
            double tMax = NBTHelper.getDouble(ctag, "t_max");
            double uMin = NBTHelper.getDouble(ctag, "u_min");
            double uMax = NBTHelper.getDouble(ctag, "u_max");
            boolean useU = NBTHelper.getBoolean(ctag, "use_u");
            int points = NBTHelper.getInt(ctag, "points");
            String colorMode = NBTHelper.hasString(ctag, "color_mode") ? NBTHelper.getString(ctag, "color_mode") : "gradient";
            double fixedR = NBTHelper.hasDouble(ctag, "fixed_r") ? NBTHelper.getDouble(ctag, "fixed_r") : 1.0;
            double fixedG = NBTHelper.hasDouble(ctag, "fixed_g") ? NBTHelper.getDouble(ctag, "fixed_g") : 1.0;
            double fixedB = NBTHelper.hasDouble(ctag, "fixed_b") ? NBTHelper.getDouble(ctag, "fixed_b") : 1.0;
            double gradStartR = NBTHelper.hasDouble(ctag, "grad_start_r") ? NBTHelper.getDouble(ctag, "grad_start_r") : 0.96;
            double gradStartG = NBTHelper.hasDouble(ctag, "grad_start_g") ? NBTHelper.getDouble(ctag, "grad_start_g") : 0.56;
            double gradStartB = NBTHelper.hasDouble(ctag, "grad_start_b") ? NBTHelper.getDouble(ctag, "grad_start_b") : 0.64;
            double gradEndR = NBTHelper.hasDouble(ctag, "grad_end_r") ? NBTHelper.getDouble(ctag, "grad_end_r") : 0.88;
            double gradEndG = NBTHelper.hasDouble(ctag, "grad_end_g") ? NBTHelper.getDouble(ctag, "grad_end_g") : 0.78;
            double gradEndB = NBTHelper.hasDouble(ctag, "grad_end_b") ? NBTHelper.getDouble(ctag, "grad_end_b") : 0.96;
            String colorExprR = NBTHelper.hasString(ctag, "color_expr_r") ? NBTHelper.getString(ctag, "color_expr_r") : "1";
            String colorExprG = NBTHelper.hasString(ctag, "color_expr_g") ? NBTHelper.getString(ctag, "color_expr_g") : "1";
            String colorExprB = NBTHelper.hasString(ctag, "color_expr_b") ? NBTHelper.getString(ctag, "color_expr_b") : "1";

            return new EquationParticleIota(
                x,
                y,
                z,
                tMin,
                tMax,
                uMin,
                uMax,
                useU,
                points,
                colorMode,
                fixedR,
                fixedG,
                fixedB,
                gradStartR,
                gradStartG,
                gradStartB,
                gradEndR,
                gradEndG,
                gradEndB,
                colorExprR,
                colorExprG,
                colorExprB
            );
        }

        @Override
        public Component display(Tag tag) {
            var ctag = HexUtils.downcast(tag, CompoundTag.TYPE);
            int points = NBTHelper.getInt(ctag, "points");
            boolean useU = NBTHelper.getBoolean(ctag, "use_u");
            String mode = NBTHelper.hasString(ctag, "color_mode") ? NBTHelper.getString(ctag, "color_mode") : "gradient";
            String x = NBTHelper.getString(ctag, "x");
            return Component.literal("EquationParticle(")
                .append(Component.literal("points=" + points + ", mode=" + (useU ? "surface" : "curve") + ", color=" + mode + ", x=" + x).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.LIGHT_PURPLE);
        }

        @Override
        public int color() {
            return 0xff_d38af5;
        }
    };

    public static void register() {
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_button"), UI_BUTTON);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_input"), UI_INPUT);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_numeric_input"), UI_NUMERIC_INPUT);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_slider"), UI_SLIDER);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_checkbox"), UI_CHECKBOX);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_select_list"), UI_SELECT_LIST);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_section"), UI_SECTION);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("intent_dropdown"), UI_DROPDOWN);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("presence_intent"), PRESENCE_INTENT);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("memory"), MEMORY);
        Registry.register(HexIotaTypes.REGISTRY, Manifestation.id("equation_particle"), EQUATION_PARTICLE);
    }
}