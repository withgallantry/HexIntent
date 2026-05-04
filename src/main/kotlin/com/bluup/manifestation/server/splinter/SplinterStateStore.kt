package com.bluup.manifestation.server.splinter

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import java.util.UUID

class SplinterStateStore : SavedData() {
    data class SplinterRecord(
        val id: UUID,
        val owner: UUID,
        var dimensionId: String,
        var position: Vec3,
        var castAtGameTime: Long,
        var castingHand: InteractionHand,
        val payloadTags: MutableList<CompoundTag>,
        var ravenmindTag: CompoundTag?
    )

    private val splintersByOwner: MutableMap<UUID, MutableMap<UUID, SplinterRecord>> = mutableMapOf()

    fun allByOwner(owner: UUID): List<SplinterRecord> = splintersByOwner[owner]?.values?.toList() ?: listOf()

    fun allOwners(): Set<UUID> = splintersByOwner.keys

    fun count(owner: UUID): Int = splintersByOwner[owner]?.size ?: 0

    fun get(owner: UUID, id: UUID): SplinterRecord? = splintersByOwner[owner]?.get(id)

    fun put(record: SplinterRecord) {
        val map = splintersByOwner.getOrPut(record.owner) { mutableMapOf() }
        map[record.id] = record
        setDirty()
    }

    fun remove(owner: UUID, id: UUID): SplinterRecord? {
        val map = splintersByOwner[owner] ?: return null
        val removed = map.remove(id)
        if (map.isEmpty()) {
            splintersByOwner.remove(owner)
        }
        if (removed != null) {
            setDirty()
        }
        return removed
    }

    fun removeOwner(owner: UUID): Boolean {
        val removed = splintersByOwner.remove(owner)
        if (removed != null) {
            setDirty()
            return true
        }
        return false
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((_, records) in splintersByOwner) {
            for ((_, record) in records) {
                val out = CompoundTag()
                out.putUUID("id", record.id)
                out.putUUID("owner", record.owner)
                out.putString("dimension", record.dimensionId)
                out.putDouble("x", record.position.x)
                out.putDouble("y", record.position.y)
                out.putDouble("z", record.position.z)
                out.putLong("cast_at", record.castAtGameTime)
                out.putString("hand", record.castingHand.name)

                val payload = ListTag()
                for (iotaTag in record.payloadTags) {
                    payload.add(iotaTag.copy())
                }
                out.put("payload", payload)
                if (record.ravenmindTag != null) {
                    out.put("ravenmind", record.ravenmindTag!!.copy())
                }
                list.add(out)
            }
        }
        tag.put("splinters", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "manifestation_splinters"

        fun get(server: MinecraftServer): SplinterStateStore {
            val storage = server.overworld().dataStorage
            return storage.computeIfAbsent(::load, ::SplinterStateStore, DATA_NAME)
        }

        private fun load(tag: CompoundTag): SplinterStateStore {
            val out = SplinterStateStore()
            val list = tag.getList("splinters", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                val t = entry as? CompoundTag ?: continue
                if (!t.hasUUID("id") || !t.hasUUID("owner")) {
                    continue
                }

                val id = t.getUUID("id")
                val owner = t.getUUID("owner")
                val dimension = t.getString("dimension")
                val pos = Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"))
                val castAt = t.getLong("cast_at")
                val handName = t.getString("hand")
                val hand = runCatching { InteractionHand.valueOf(handName) }.getOrElse { InteractionHand.MAIN_HAND }

                val payload = mutableListOf<CompoundTag>()
                if (t.contains("payload", Tag.TAG_LIST.toInt())) {
                    val payloadList = t.getList("payload", Tag.TAG_COMPOUND.toInt())
                    for (i in 0 until payloadList.size) {
                        payload.add(payloadList.getCompound(i).copy())
                    }
                }
                val ravenmind = if (t.contains("ravenmind", Tag.TAG_COMPOUND.toInt())) {
                    t.getCompound("ravenmind").copy()
                } else {
                    null
                }

                val record = SplinterRecord(
                    id = id,
                    owner = owner,
                    dimensionId = dimension,
                    position = pos,
                    castAtGameTime = castAt,
                    castingHand = hand,
                    payloadTags = payload,
                    ravenmindTag = ravenmind
                )
                out.put(record)
            }
            return out
        }
    }
}
