package com.bluup.manifestation.server.splinter

import com.bluup.manifestation.server.KotlinNbtCompat
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
        var anchorPosition: Vec3?,
        var circleImpetusPos: Vec3?,
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
                KotlinNbtCompat.putUUID(out, "id", record.id)
                KotlinNbtCompat.putUUID(out, "owner", record.owner)
                KotlinNbtCompat.putString(out, "dimension", record.dimensionId)
                KotlinNbtCompat.putDouble(out, "x", record.position.x)
                KotlinNbtCompat.putDouble(out, "y", record.position.y)
                KotlinNbtCompat.putDouble(out, "z", record.position.z)
                if (record.anchorPosition != null) {
                    KotlinNbtCompat.putDouble(out, "anchor_x", record.anchorPosition!!.x)
                    KotlinNbtCompat.putDouble(out, "anchor_y", record.anchorPosition!!.y)
                    KotlinNbtCompat.putDouble(out, "anchor_z", record.anchorPosition!!.z)
                }
                if (record.circleImpetusPos != null) {
                    KotlinNbtCompat.putDouble(out, "circle_impetus_x", record.circleImpetusPos!!.x)
                    KotlinNbtCompat.putDouble(out, "circle_impetus_y", record.circleImpetusPos!!.y)
                    KotlinNbtCompat.putDouble(out, "circle_impetus_z", record.circleImpetusPos!!.z)
                }
                KotlinNbtCompat.putLong(out, "cast_at", record.castAtGameTime)
                KotlinNbtCompat.putString(out, "hand", record.castingHand.name)

                val payload = ListTag()
                for (iotaTag in record.payloadTags) {
                    payload.add(iotaTag.copy())
                }
                KotlinNbtCompat.put(out, "payload", payload)
                if (record.ravenmindTag != null) {
                    KotlinNbtCompat.put(out, "ravenmind", record.ravenmindTag!!.copy())
                }
                list.add(out)
            }
        }
        KotlinNbtCompat.put(tag, "splinters", list)
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
            val list = KotlinNbtCompat.getList(tag, "splinters", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                val t = entry as? CompoundTag ?: continue
                if (!KotlinNbtCompat.hasUUID(t, "id") || !KotlinNbtCompat.hasUUID(t, "owner")) {
                    continue
                }

                val id = KotlinNbtCompat.getUUID(t, "id")
                val owner = KotlinNbtCompat.getUUID(t, "owner")
                val dimension = KotlinNbtCompat.getString(t, "dimension")
                val pos = Vec3(KotlinNbtCompat.getDouble(t, "x"), KotlinNbtCompat.getDouble(t, "y"), KotlinNbtCompat.getDouble(t, "z"))
                val anchorPos = if (KotlinNbtCompat.contains(t, "anchor_x", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "anchor_y", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "anchor_z", Tag.TAG_DOUBLE.toInt())
                ) {
                    Vec3(KotlinNbtCompat.getDouble(t, "anchor_x"), KotlinNbtCompat.getDouble(t, "anchor_y"), KotlinNbtCompat.getDouble(t, "anchor_z"))
                } else {
                    null
                }
                val castAt = KotlinNbtCompat.getLong(t, "cast_at")
                val handName = KotlinNbtCompat.getString(t, "hand")
                val hand = runCatching { InteractionHand.valueOf(handName) }.getOrElse { InteractionHand.MAIN_HAND }
                val circleImpetusPos = if (KotlinNbtCompat.contains(t, "circle_impetus_x", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "circle_impetus_y", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "circle_impetus_z", Tag.TAG_DOUBLE.toInt())
                ) {
                    Vec3(KotlinNbtCompat.getDouble(t, "circle_impetus_x"), KotlinNbtCompat.getDouble(t, "circle_impetus_y"), KotlinNbtCompat.getDouble(t, "circle_impetus_z"))
                } else {
                    null
                }

                val payload = mutableListOf<CompoundTag>()
                if (KotlinNbtCompat.contains(t, "payload", Tag.TAG_LIST.toInt())) {
                    val payloadList = KotlinNbtCompat.getList(t, "payload", Tag.TAG_COMPOUND.toInt())
                    for (i in 0 until payloadList.size) {
                        payload.add(payloadList.getCompound(i).copy())
                    }
                }
                val ravenmind = if (KotlinNbtCompat.contains(t, "ravenmind", Tag.TAG_COMPOUND.toInt())) {
                    KotlinNbtCompat.getCompound(t, "ravenmind").copy()
                } else {
                    null
                }

                val record = SplinterRecord(
                    id = id,
                    owner = owner,
                    dimensionId = dimension,
                    position = pos,
                    anchorPosition = anchorPos,
                    circleImpetusPos = circleImpetusPos,
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
