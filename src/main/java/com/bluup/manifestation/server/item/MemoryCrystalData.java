package com.bluup.manifestation.server.item;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.iota.IotaType;
import at.petrak.hexcasting.api.casting.iota.ListIota;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
        if (stack.isEmpty() || !stack.hasTag()) {
            return false;
        }

        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(TAG_MEMORY_ROOT, Tag.TAG_COMPOUND)) {
            return false;
        }

        CompoundTag memory = root.getCompound(TAG_MEMORY_ROOT);
        return memory.contains(TAG_MEMORY_ID, Tag.TAG_STRING) && isValidMemoryId(memory.getString(TAG_MEMORY_ID));
    }

    public static String ensureMemoryId(ItemStack stack) {
        CompoundTag memory = getOrCreateMemoryTag(stack);
        String existing = memory.getString(TAG_MEMORY_ID);
        if (isValidMemoryId(existing)) {
            return existing;
        }

        String generated = generateMemoryId();
        memory.putString(TAG_MEMORY_ID, generated);
        return generated;
    }

    public static @Nullable String getMemoryId(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return null;
        }

        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(TAG_MEMORY_ROOT, Tag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag memory = root.getCompound(TAG_MEMORY_ROOT);
        String id = memory.getString(TAG_MEMORY_ID);
        return isValidMemoryId(id) ? id : null;
    }

    public static void writePatterns(ItemStack stack, ListIota patterns) {
        CompoundTag memory = getOrCreateMemoryTag(stack);
        memory.put(TAG_MEMORY_PATTERNS, IotaType.serialize(patterns));
    }

    public static @Nullable ListIota readPatterns(ItemStack stack, ServerLevel world) {
        if (stack.isEmpty() || !stack.hasTag()) {
            return null;
        }

        CompoundTag root = stack.getTag();
        if (root == null || !root.contains(TAG_MEMORY_ROOT, Tag.TAG_COMPOUND)) {
            return null;
        }

        CompoundTag memory = root.getCompound(TAG_MEMORY_ROOT);
        if (!memory.contains(TAG_MEMORY_PATTERNS, Tag.TAG_LIST)) {
            return new ListIota(java.util.List.of());
        }

        Iota decoded = ListIota.TYPE.deserialize(memory.get(TAG_MEMORY_PATTERNS), world);
        return decoded instanceof ListIota listIota ? listIota : null;
    }

    public static void copyMemoryData(ItemStack from, ItemStack to) {
        if (from.isEmpty() || to.isEmpty() || !from.hasTag()) {
            return;
        }

        CompoundTag sourceTag = from.getTag();
        if (sourceTag == null || !sourceTag.contains(TAG_MEMORY_ROOT, Tag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag copiedMemory = sourceTag.getCompound(TAG_MEMORY_ROOT).copy();
        to.getOrCreateTag().put(TAG_MEMORY_ROOT, copiedMemory);
    }

    private static CompoundTag getOrCreateMemoryTag(ItemStack stack) {
        CompoundTag root = stack.getOrCreateTag();
        if (!root.contains(TAG_MEMORY_ROOT, Tag.TAG_COMPOUND)) {
            root.put(TAG_MEMORY_ROOT, new CompoundTag());
        }
        return root.getCompound(TAG_MEMORY_ROOT);
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
