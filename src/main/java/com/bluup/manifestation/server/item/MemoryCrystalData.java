package com.bluup.manifestation.server.item;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.NullIota;
import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

public final class MemoryCrystalData {
    public static final String TAG_MEMORY_ROOT = "manifestation_memory_crystal";
    public static final String TAG_MEMORY_ID = "id";
    public static final String TAG_MEMORY_PATTERNS = "patterns";

    private static final int MEMORY_ID_LENGTH = 6;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private MemoryCrystalData() {
    }

    public static boolean isMemoryCarrier(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        return stack.getItem() == ManifestationItems.MEMORY_CRYSTAL || hasMemoryData(stack);
    }

    public static boolean hasMemoryData(ItemStack stack) {
        if (stack.isEmpty() || !NBTHelper.hasCompound(stack, TAG_MEMORY_ROOT)) {
            return false;
        }

        CompoundTag memory = NBTHelper.getCompound(stack, TAG_MEMORY_ROOT);
        return NBTHelper.hasString(memory, TAG_MEMORY_ID) && isValidMemoryId(NBTHelper.getString(memory, TAG_MEMORY_ID));
    }

    public static String ensureMemoryId(ItemStack stack) {
        CompoundTag memory = getOrCreateMemoryTag(stack);
        String existing = NBTHelper.getString(memory, TAG_MEMORY_ID);
        if (isValidMemoryId(existing)) {
            return existing;
        }

        String generated = generateMemoryId();
        NBTHelper.putString(memory, TAG_MEMORY_ID, generated);
        return generated;
    }

    public static @Nullable String getMemoryId(ItemStack stack) {
        if (stack.isEmpty() || !NBTHelper.hasCompound(stack, TAG_MEMORY_ROOT)) {
            return null;
        }

        CompoundTag memory = NBTHelper.getCompound(stack, TAG_MEMORY_ROOT);
        String id = NBTHelper.getString(memory, TAG_MEMORY_ID);
        return isValidMemoryId(id) ? id : null;
    }

    public static void writeStoredIota(ItemStack stack, Iota iota) {
        CompoundTag memory = getOrCreateMemoryTag(stack);
        NBTHelper.put(memory, TAG_MEMORY_PATTERNS, IotaType.serialize(iota));
    }

    public static @Nullable Iota readStoredIota(ItemStack stack, ServerLevel world) {
        if (stack.isEmpty() || !NBTHelper.hasCompound(stack, TAG_MEMORY_ROOT)) {
            return null;
        }

        CompoundTag memory = NBTHelper.getCompound(stack, TAG_MEMORY_ROOT);
        if (!NBTHelper.hasCompound(memory, TAG_MEMORY_PATTERNS)) {
            return new NullIota();
        }

        return IotaType.deserialize(NBTHelper.getCompound(memory, TAG_MEMORY_PATTERNS), world);
    }

    public static void copyMemoryData(ItemStack from, ItemStack to) {
        if (from.isEmpty() || to.isEmpty() || !NBTHelper.hasCompound(from, TAG_MEMORY_ROOT)) {
            return;
        }

        CompoundTag copiedMemory = NBTHelper.getCompound(from, TAG_MEMORY_ROOT).copy();
        NBTHelper.putCompound(to, TAG_MEMORY_ROOT, copiedMemory);
    }

    private static CompoundTag getOrCreateMemoryTag(ItemStack stack) {
        return NBTHelper.getOrCreateCompound(stack, TAG_MEMORY_ROOT);
    }

    private static boolean isValidMemoryId(String id) {
        if (id == null || id.length() != MEMORY_ID_LENGTH) {
            return false;
        }

        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetter(c)) {
                return false;
            }
        }

        return true;
    }

    private static String generateMemoryId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder out = new StringBuilder(MEMORY_ID_LENGTH);
        for (int i = 0; i < MEMORY_ID_LENGTH; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
