package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class MemoryIota extends Iota {
    private final String id;

    public MemoryIota(String id) {
        super(ManifestationUiIotaTypes.MEMORY, List.of(id));
        this.id = Objects.requireNonNull(id, "id");
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        return that instanceof MemoryIota other && this.id.equals(other.id);
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.putString("id", id);
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        return List.of();
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public int depth() {
        return 1;
    }
}
