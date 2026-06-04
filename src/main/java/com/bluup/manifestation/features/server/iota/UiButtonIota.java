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

public final class UiButtonIota extends Iota {
    private final Iota label;
    private final List<Iota> actions;
    private final int depth;
    private final int size;

    public UiButtonIota(Iota label, List<Iota> actions) {
        super(ManifestationUiIotaTypes.UI_BUTTON, List.of(label, List.copyOf(actions)));
        this.label = label;
        this.actions = List.copyOf(actions);

        int maxChildDepth = label.depth();
        int totalSize = 1 + label.size();
        for (Iota action : actions) {
            totalSize += action.size();
            if (action.depth() > maxChildDepth) {
                maxChildDepth = action.depth();
            }
        }
        this.depth = maxChildDepth + 1;
        this.size = totalSize;
    }

    public Iota getLabel() {
        return label;
    }

    public List<Iota> getActions() {
        return actions;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof UiButtonIota other)) {
            return false;
        }
        if (!Iota.tolerates(this.label, other.label)) {
            return false;
        }
        if (this.actions.size() != other.actions.size()) {
            return false;
        }
        for (int i = 0; i < this.actions.size(); i++) {
            if (!Iota.tolerates(this.actions.get(i), other.actions.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        NBTHelper.put(out, "label", IotaType.serialize(label));
        ListTag actionList = new ListTag();
        for (Iota action : actions) {
            actionList.add(IotaType.serialize(action));
        }
        NBTHelper.putList(out, "actions", actionList);
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        ArrayList<Iota> subs = new ArrayList<>(1 + actions.size());
        subs.add(label);
        subs.addAll(actions);
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