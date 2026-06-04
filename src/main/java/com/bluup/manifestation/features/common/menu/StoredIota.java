package com.bluup.manifestation.common.menu;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/**
 * A single iota stored for later re-dispatch when a menu button is clicked.
 *
 * <p>Uses Hex Casting's {@link IotaType#serialize(Iota)} and
 * {@link IotaType#deserialize(CompoundTag, ServerLevel)} round-trip. This means
 * anything Hex knows how to represent as an iota — patterns, numbers, vectors,
 * strings (MoreIotas), entities, custom addon iotas like Ioticblocks' View —
 * all survive the trip across the wire intact.
 *
 * <p>On the wire we transmit the IotaType's compound tag. Deserialization
 * requires a {@link ServerLevel} (because some iota types need the world to
 * resolve references, e.g. entity lookups), so deserialize is a server-side
 * operation gated through {@link #toIota(ServerLevel)}.
 *
 * <p>This replaces the old {@code StoredPattern} class, which could only
 * store patterns as (signature, start-dir) pairs. The old approach rejected
 * any non-pattern iota that the player had Consideration-escaped or pushed
 * onto the stack as a literal — which turned out to be the bug preventing
 * buttons with View/literal iotas from registering.
 */
public final class StoredIota {
    private final CompoundTag tag;

    public StoredIota(CompoundTag tag) {
        this.tag = Objects.requireNonNull(tag, "tag").copy();
    }

    /**
     * Construct from a live iota. Takes a snapshot of its NBT at construction
     * time — the source iota can be mutated or garbage-collected after.
     */
    public static StoredIota of(Iota iota) {
        return new StoredIota(IotaType.serialize(iota));
    }

    /** The raw NBT — mostly useful for the networking layer. */
    public CompoundTag tag() {
        return tag.copy();
    }

    /**
     * Rehydrate into a real Iota. Needs a ServerLevel because some iota types
     * (entities most notably) do live lookups during deserialization.
     * Returns a {@code GarbageIota} if deserialization fails — Hex's own
     * convention for irrecoverable iotas.
     */
    public Iota toIota(ServerLevel world) {
        return IotaType.deserialize(tag.copy(), world);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeNbt(tag);
    }

    public static StoredIota read(FriendlyByteBuf buf) {
        CompoundTag t = buf.readNbt();
        if (t == null) {
            throw new IllegalStateException("StoredIota: received null NBT on wire");
        }
        return new StoredIota(t);
    }

    @Override
    public String toString() {
        return "StoredIota[" + tag + "]";
    }
}
