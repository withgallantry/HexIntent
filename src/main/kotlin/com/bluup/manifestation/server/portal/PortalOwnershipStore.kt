package com.bluup.manifestation.server

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class PortalOwnershipStore : SavedData() {
    data class PortalEndpoint(val dimensionId: String, val pos: BlockPos)

    data class PortalPair(val first: PortalEndpoint, val second: PortalEndpoint)

    private val ownedPortalPairs: MutableMap<UUID, PortalPair> = mutableMapOf()

    fun get(owner: UUID): PortalPair? = ownedPortalPairs[owner]

    fun put(owner: UUID, pair: PortalPair) {
        ownedPortalPairs[owner] = pair
        setDirty()
    }

    fun remove(owner: UUID) {
        if (ownedPortalPairs.remove(owner) != null) {
            setDirty()
        }
    }

    fun removeIfContains(owner: UUID, endpoint: PortalEndpoint) {
        val pair = ownedPortalPairs[owner] ?: return
        if (pair.first != endpoint && pair.second != endpoint) {
            return
        }

        ownedPortalPairs.remove(owner)
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((owner, pair) in ownedPortalPairs) {
            val entry = CompoundTag()
            KotlinNbtCompat.putUUID(entry, "owner", owner)
            KotlinNbtCompat.putString(entry, "first_dim", pair.first.dimensionId)
            KotlinNbtCompat.putLong(entry, "first_pos", pair.first.pos.asLong())
            KotlinNbtCompat.putString(entry, "second_dim", pair.second.dimensionId)
            KotlinNbtCompat.putLong(entry, "second_pos", pair.second.pos.asLong())
            list.add(entry)
        }
        KotlinNbtCompat.put(tag, "portal_pairs", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "manifestation_owned_portal_pairs"

        fun get(server: MinecraftServer): PortalOwnershipStore {
            return server.overworld().dataStorage.computeIfAbsent(
                ::load,
                ::PortalOwnershipStore,
                DATA_NAME
            )
        }

        private fun load(tag: CompoundTag): PortalOwnershipStore {
            val out = PortalOwnershipStore()
            val list = KotlinNbtCompat.getList(tag, "portal_pairs", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                val data = entry as? CompoundTag ?: continue
                if (!KotlinNbtCompat.hasUUID(data, "owner")) {
                    continue
                }

                val owner = KotlinNbtCompat.getUUID(data, "owner")
                val first = PortalEndpoint(
                    KotlinNbtCompat.getString(data, "first_dim"),
                    BlockPos.of(KotlinNbtCompat.getLong(data, "first_pos"))
                )
                val second = PortalEndpoint(
                    KotlinNbtCompat.getString(data, "second_dim"),
                    BlockPos.of(KotlinNbtCompat.getLong(data, "second_pos"))
                )
                out.ownedPortalPairs[owner] = PortalPair(first, second)
            }
            return out
        }
    }
}