package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class UiDropdownIota extends Iota {
    private final Iota label;
    private final List<Iota> options;
    private final int selectedIndex;
    private final int depth;
    private final int size;

    public UiDropdownIota(Iota label, List<Iota> options, int selectedIndex) {
        super(ManifestationUiIotaTypes.UI_DROPDOWN, List.of(label, List.copyOf(options), selectedIndex));
        this.label = label;
        this.options = List.copyOf(options);
        this.selectedIndex = this.options.isEmpty()
            ? 0
            : Math.max(0, Math.min(selectedIndex, this.options.size() - 1));
        int maxChildDepth = label.depth();
        int totalSize = 1 + label.size();
        for (Iota option : this.options) {
            totalSize += option.size();
            if (option.depth() > maxChildDepth) {
                maxChildDepth = option.depth();
            }
        }
        this.depth = maxChildDepth + 1;
        this.size = totalSize;
    }

    public Iota getLabel() {
        return label;
    }

    public List<Iota> getOptions() {
        return options;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof UiDropdownIota other)) {
            return false;
        }
        return Iota.tolerates(this.label, other.label)
            && this.options.equals(other.options)
            && this.selectedIndex == other.selectedIndex;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        NBTHelper.put(out, "label", IotaType.serialize(label));
        ListTag optionTags = new ListTag();
        for (Iota option : options) {
            optionTags.add(IotaType.serialize(option));
        }
        NBTHelper.putList(out, "options", optionTags);
        NBTHelper.putInt(out, "selected", selectedIndex);
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        ArrayList<Iota> subs = new ArrayList<>(1 + options.size());
        subs.add(label);
        subs.addAll(options);
        return subs;
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