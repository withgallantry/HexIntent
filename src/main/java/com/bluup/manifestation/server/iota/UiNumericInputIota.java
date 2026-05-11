package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class UiNumericInputIota extends Iota {
    private final Iota label;
    private final boolean hasCurrent;
    private final double current;
    private final int depth;
    private final int size;

    public UiNumericInputIota(Iota label, Double current) {
        super(
            ManifestationUiIotaTypes.UI_NUMERIC_INPUT,
            List.of(label, current != null, current != null ? current : 0.0)
        );
        this.label = label;
        this.hasCurrent = current != null;
        this.current = current != null ? current : 0.0;
        this.depth = label.depth() + 1;
        this.size = 1 + label.size();
    }

    public Iota getLabel() {
        return label;
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
        if (!(that instanceof UiNumericInputIota other)) {
            return false;
        }
        return Iota.tolerates(this.label, other.label)
            && this.hasCurrent == other.hasCurrent
            && Math.abs(this.current - other.current) < 0.0001;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.put("label", IotaType.serialize(label));
        out.putBoolean("has_current", hasCurrent);
        if (hasCurrent) {
            out.put("current", DoubleTag.valueOf(current));
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
