package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class UiSelectListIota extends Iota {
    private final Iota label;
    private final List<Iota> options;
    private final int maxRows;
    private final boolean multiSelect;
    private final int depth;
    private final int size;

    public UiSelectListIota(Iota label, List<Iota> options, int maxRows, boolean multiSelect) {
        super(
            ManifestationUiIotaTypes.UI_SELECT_LIST,
            List.of(label, List.copyOf(options), maxRows, multiSelect)
        );
        this.label = label;
        this.options = List.copyOf(options);
        this.maxRows = Math.max(1, Math.min(maxRows, 12));
        this.multiSelect = multiSelect;

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

    public int getMaxRows() {
        return maxRows;
    }

    public boolean isMultiSelect() {
        return multiSelect;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof UiSelectListIota other)) {
            return false;
        }
        return Iota.tolerates(this.label, other.label)
            && this.options.equals(other.options)
            && this.maxRows == other.maxRows
            && this.multiSelect == other.multiSelect;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.put("label", IotaType.serialize(label));

        ListTag optionTags = new ListTag();
        for (Iota option : options) {
            optionTags.add(IotaType.serialize(option));
        }
        out.put("options", optionTags);
        out.put("max_rows", IntTag.valueOf(maxRows));
        out.put("multi_select", ByteTag.valueOf(multiSelect));
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
