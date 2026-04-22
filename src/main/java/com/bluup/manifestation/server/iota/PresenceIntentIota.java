package com.bluup.manifestation.server.iota;

import at.petrak.hexcasting.api.casting.iota.DoubleIota;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.utils.HexUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class PresenceIntentIota extends Iota {
    private final Vec3 position;
    private final Vec3 facing;
    private final String dimensionId;

    public PresenceIntentIota(Vec3 position, Vec3 facing, String dimensionId) {
        super(ManifestationUiIotaTypes.PRESENCE_INTENT, List.of(position, facing, dimensionId));
        this.position = sanitize(position);
        this.facing = sanitize(facing);
        this.dimensionId = dimensionId;
    }

    public Vec3 getPosition() {
        return position;
    }

    public Vec3 getFacing() {
        return facing;
    }

    public String getDimensionId() {
        return dimensionId;
    }

    @Override
    public boolean isTruthy() {
        return true;
    }

    @Override
    protected boolean toleratesOther(Iota that) {
        if (!(that instanceof PresenceIntentIota other)) {
            return false;
        }

        double tolSq = DoubleIota.TOLERANCE * DoubleIota.TOLERANCE;
        return this.position.distanceToSqr(other.position) <= tolSq
            && this.facing.distanceToSqr(other.facing) <= tolSq
            && this.dimensionId.equals(other.dimensionId);
    }

    @Override
    public @NotNull Tag serialize() {
        CompoundTag out = new CompoundTag();
        out.put("position", HexUtils.serializeToNBT(position));
        out.put("facing", HexUtils.serializeToNBT(facing));
        out.putString("dimension", dimensionId);
        return out;
    }

    @Override
    public @Nullable Iterable<Iota> subIotas() {
        return List.of(
            new at.petrak.hexcasting.api.casting.iota.Vec3Iota(position),
            new at.petrak.hexcasting.api.casting.iota.Vec3Iota(facing)
        );
    }

    @Override
    public int size() {
        return 4;
    }

    @Override
    public int depth() {
        return 3;
    }

    private static Vec3 sanitize(Vec3 in) {
        return new Vec3(
            HexUtils.fixNAN(in.x),
            HexUtils.fixNAN(in.y),
            HexUtils.fixNAN(in.z)
        );
    }
}