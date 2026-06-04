package com.bluup.manifestation.server

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import java.util.UUID

object KotlinNbtCompat {
    fun contains(stack: ItemStack, key: String): Boolean = KotlinNbtBridge.contains(stack, key)

    fun putBoolean(stack: ItemStack, key: String, value: Boolean) {
        KotlinNbtBridge.putBoolean(stack, key, value)
    }

    fun putString(stack: ItemStack, key: String, value: String) {
        KotlinNbtBridge.putString(stack, key, value)
    }

    fun remove(stack: ItemStack, key: String) {
        KotlinNbtBridge.remove(stack, key)
    }

    fun getBoolean(stack: ItemStack, key: String): Boolean = KotlinNbtBridge.getBoolean(stack, key)

    fun getString(stack: ItemStack, key: String): String = KotlinNbtBridge.getString(stack, key)

    fun contains(tag: CompoundTag, key: String): Boolean = KotlinNbtBridge.contains(tag, key)

    fun contains(tag: CompoundTag, key: String, typeId: Int): Boolean = KotlinNbtBridge.contains(tag, key, typeId)

    fun hasUUID(tag: CompoundTag, key: String): Boolean = KotlinNbtBridge.hasUUID(tag, key)

    fun getString(tag: CompoundTag, key: String): String = KotlinNbtBridge.getString(tag, key)

    fun getLong(tag: CompoundTag, key: String): Long = KotlinNbtBridge.getLong(tag, key)

    fun getFloat(tag: CompoundTag, key: String): Float = KotlinNbtBridge.getFloat(tag, key)

    fun getInt(tag: CompoundTag, key: String): Int = KotlinNbtBridge.getInt(tag, key)

    fun getDouble(tag: CompoundTag, key: String): Double = KotlinNbtBridge.getDouble(tag, key)

    fun getBoolean(tag: CompoundTag, key: String): Boolean = KotlinNbtBridge.getBoolean(tag, key)

    fun getUUID(tag: CompoundTag, key: String): UUID = KotlinNbtBridge.getUUID(tag, key)

    fun getCompound(tag: CompoundTag, key: String): CompoundTag = KotlinNbtBridge.getCompound(tag, key)

    fun getLongArray(tag: CompoundTag, key: String): LongArray = KotlinNbtBridge.getLongArray(tag, key)

    fun getList(tag: CompoundTag, key: String, typeId: Int): ListTag = KotlinNbtBridge.getList(tag, key, typeId)

    fun putString(tag: CompoundTag, key: String, value: String) {
        KotlinNbtBridge.putString(tag, key, value)
    }

    fun putLong(tag: CompoundTag, key: String, value: Long) {
        KotlinNbtBridge.putLong(tag, key, value)
    }

    fun putLongArray(tag: CompoundTag, key: String, value: LongArray) {
        KotlinNbtBridge.putLongArray(tag, key, value)
    }

    fun putFloat(tag: CompoundTag, key: String, value: Float) {
        KotlinNbtBridge.putFloat(tag, key, value)
    }

    fun putInt(tag: CompoundTag, key: String, value: Int) {
        KotlinNbtBridge.putInt(tag, key, value)
    }

    fun putDouble(tag: CompoundTag, key: String, value: Double) {
        KotlinNbtBridge.putDouble(tag, key, value)
    }

    fun putBoolean(tag: CompoundTag, key: String, value: Boolean) {
        KotlinNbtBridge.putBoolean(tag, key, value)
    }

    fun putUUID(tag: CompoundTag, key: String, value: UUID) {
        KotlinNbtBridge.putUUID(tag, key, value)
    }

    fun put(tag: CompoundTag, key: String, value: Tag) {
        KotlinNbtBridge.putTag(tag, key, value)
    }
}