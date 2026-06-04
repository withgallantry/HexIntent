package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class UiSliderIota extends Iota {
    private final Iota label;
    private final double min;
    private final double max;
    private final boolean hasCurrent;
    private final double current;
    private final int depth;
    private final int size;

    public UiSliderIota(Iota label, double min, double max, Double current) {
        super(
            ManifestationUiIotaTypes.UI_SLIDER,
            List.of(label, min, max, current != null, current != null ? current : min)
        );
        this.label = label;
        this.min = min;
        this.max = max;
        this.hasCurrent = current != null;
        this.current = current != null ? current : min;
        this.depth = label.depth() + 1;
        this.size = 1 + label.size();
    }

    public Iota getLabel() {
        return label;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public boolean hasCurrent() {
        return hasCurrent;
    }

    public double getCurrent() {
        return current;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof UiSliderIota other)) {
            return false;
        }
        return Iota.tolerates(this.label, other.label)
            && Math.abs(this.min - other.min) < 0.0001
            && Math.abs(this.max - other.max) < 0.0001
            && this.hasCurrent == other.hasCurrent
            && Math.abs(this.current - other.current) < 0.0001;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        NBTHelper.put(out, "label", IotaType.serialize(label));
        NBTHelper.putDouble(out, "min", min);
        NBTHelper.putDouble(out, "max", max);
        NBTHelper.putBoolean(out, "has_current", hasCurrent);
        if (hasCurrent) {
            NBTHelper.putDouble(out, "current", current);
        }
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        return List.of(label);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public int depth() {
        return depth;
    }
}