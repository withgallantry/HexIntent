package com.bluup.manifestation.server.data

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.concurrent.ConcurrentHashMap

class ServerMemoryStorage : SavedData() {
    private val memories = ConcurrentHashMap<String, CompoundTag>()

    fun store(memoryId: String, iota: Iota) {
        memories[memoryId] = IotaType.serialize(iota) as CompoundTag
        setDirty()
    }

    fun retrieve(memoryId: String, level: ServerLevel): Iota? {
        val tag = memories[memoryId] ?: return null
        return IotaType.deserialize(tag, level)
    }

    fun has(memoryId: String): Boolean = memories.containsKey(memoryId)

    fun delete(memoryId: String) {
        if (memories.remove(memoryId) != null) {
            setDirty()
        }
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, iotaTag) in memories) {
            val entry = CompoundTag()
            entry.putString("id", id)
            entry.put("iota", iotaTag)
            list.add(entry)
        }
        tag.put("memories", list)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): ServerMemoryStorage {
            val storage = ServerMemoryStorage()
            val list = tag.getList("memories", 10)
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                val id = entry.getString("id")
                if (id.isNotEmpty() && entry.contains("iota", 10)) { // 10 = CompoundTag
                    storage.memories[id] = entry.getCompound("iota")
                }
            }
            return storage
        }

        /** Глобальное хранилище — всегда overworld, чтобы работало в любых измерениях */
        fun get(server: MinecraftServer): ServerMemoryStorage {
            return server.overworld().dataStorage.computeIfAbsent(
                { load(it) },
                { ServerMemoryStorage() },
                "manifestation_memories"
            )
        }

        fun get(level: ServerLevel): ServerMemoryStorage = get(level.server)
    }
}