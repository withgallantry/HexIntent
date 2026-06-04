package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class UiSectionIota extends Iota {
    private final Iota label;
    private final int depth;
    private final int size;

    public UiSectionIota(Iota label) {
        super(ManifestationUiIotaTypes.UI_SECTION, label);
        this.label = label;
        this.depth = label.depth() + 1;
        this.size = 1 + label.size();
    }

    public Iota getLabel() {
        return label;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return that instanceof UiSectionIota other && Iota.tolerates(this.label, other.label);
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        NBTHelper.put(out, "label", IotaType.serialize(label));
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