package com.bluup.manifestation.server;

import at.petrak.hexcasting.api.utils.NBTHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Java bridge for Hex Casting's NBTHelper.
 */
public final class KotlinNbtBridge {
    private KotlinNbtBridge() {
    }

    public static boolean contains(ItemStack stack, String key) {
        return NBTHelper.contains(stack, key);
    }

    public static void putBoolean(ItemStack stack, String key, boolean value) {
        NBTHelper.putBoolean(stack, key, value);
    }

    public static void putString(ItemStack stack, String key, String value) {
        NBTHelper.putString(stack, key, value);
    }

    public static void remove(ItemStack stack, String key) {
        NBTHelper.remove(stack, key);
    }

    public static boolean getBoolean(ItemStack stack, String key) {
        return NBTHelper.getBoolean(stack, key, false);
    }

    public static String getString(ItemStack stack, String key) {
        String value = NBTHelper.getString(stack, key);
        return value != null ? value : "";
    }

    public static boolean contains(CompoundTag tag, String key) {
        return NBTHelper.contains(tag, key);
    }

    public static boolean contains(CompoundTag tag, String key, int typeId) {
        return NBTHelper.contains(tag, key, typeId);
    }

    public static boolean hasUUID(CompoundTag tag, String key) {
        return NBTHelper.hasUUID(tag, key);
    }

    public static String getString(CompoundTag tag, String key) {
        String value = NBTHelper.getString(tag, key);
        return value != null ? value : "";
    }

    public static long getLong(CompoundTag tag, String key) {
        return NBTHelper.getLong(tag, key, 0L);
    }

    public static float getFloat(CompoundTag tag, String key) {
        return NBTHelper.getFloat(tag, key, 0f);
    }

    public static int getInt(CompoundTag tag, String key) {
        return NBTHelper.getInt(tag, key, 0);
    }

    public static double getDouble(CompoundTag tag, String key) {
        return NBTHelper.getDouble(tag, key, 0.0);
    }

    public static boolean getBoolean(CompoundTag tag, String key) {
        return NBTHelper.getBoolean(tag, key, false);
    }

    public static java.util.UUID getUUID(CompoundTag tag, String key) {
        return NBTHelper.getUUID(tag, key);
    }

    public static CompoundTag getCompound(CompoundTag tag, String key) {
        CompoundTag value = NBTHelper.getCompound(tag, key);
        return value != null ? value : new CompoundTag();
    }

    public static long[] getLongArray(CompoundTag tag, String key) {
        long[] value = NBTHelper.getLongArray(tag, key);
        return value != null ? value : new long[0];
    }

    public static ListTag getList(CompoundTag tag, String key, int typeId) {
        ListTag value = NBTHelper.getList(tag, key, typeId);
        return value != null ? value : new ListTag();
    }

    public static void putString(CompoundTag tag, String key, String value) {
        NBTHelper.putString(tag, key, value);
    }

    public static void putLong(CompoundTag tag, String key, long value) {
        NBTHelper.putLong(tag, key, value);
    }

    public static void putLongArray(CompoundTag tag, String key, long[] value) {
        NBTHelper.putLongArray(tag, key, value);
    }

    public static void putFloat(CompoundTag tag, String key, float value) {
        NBTHelper.putFloat(tag, key, value);
    }

    public static void putInt(CompoundTag tag, String key, int value) {
        NBTHelper.putInt(tag, key, value);
    }

    public static void putDouble(CompoundTag tag, String key, double value) {
        NBTHelper.putDouble(tag, key, value);
    }

    public static void putBoolean(CompoundTag tag, String key, boolean value) {
        NBTHelper.putBoolean(tag, key, value);
    }

    public static void putUUID(CompoundTag tag, String key, java.util.UUID value) {
        NBTHelper.putUUID(tag, key, value);
    }

    public static void putTag(CompoundTag tag, String key, Tag value) {
        NBTHelper.put(tag, key, value);
    }
}