package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class UiCheckboxIota extends Iota {
    private final Iota label;
    private final boolean checked;
    private final int depth;
    private final int size;

    public UiCheckboxIota(Iota label, boolean checked) {
        super(ManifestationUiIotaTypes.UI_CHECKBOX, List.of(label, checked));
        this.label = label;
        this.checked = checked;
        this.depth = label.depth() + 1;
        this.size = 1 + label.size();
    }

    public Iota getLabel() {
        return label;
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof UiCheckboxIota other)) {
            return false;
        }
        return Iota.tolerates(this.label, other.label) && this.checked == other.checked;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.put("label", IotaType.serialize(label));
        out.putBoolean("checked", checked);
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
