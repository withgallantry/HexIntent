package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class ParticleBlobIota extends Iota {
    private final byte[] blob;
    private final int pointCount;
    private final int virtualWeight;

    public ParticleBlobIota(byte[] blob, int pointCount, int virtualWeight) {
        super(ManifestationUiIotaTypes.PARTICLE_BLOB, List.of(pointCount, virtualWeight));
        this.blob = Objects.requireNonNull(blob, "blob").clone();
        this.pointCount = Math.max(0, pointCount);
        this.virtualWeight = Math.max(1, virtualWeight);
    }

    public byte[] getBlob() {
        return blob.clone();
    }

    public int getPointCount() {
        return pointCount;
    }

    public int getVirtualWeight() {
        return virtualWeight;
    }

    public int getCompressedBytes() {
        return blob.length;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof ParticleBlobIota other)) {
            return false;
        }
        if (this.pointCount != other.pointCount || this.virtualWeight != other.virtualWeight) {
            return false;
        }
        if (this.blob.length != other.blob.length) {
            return false;
        }
        for (int i = 0; i < this.blob.length; i++) {
            if (this.blob[i] != other.blob[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.putByteArray("blob", blob);
        out.putInt("points", pointCount);
        out.putInt("weight", virtualWeight);
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        return null;
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