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

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((owner, pair) in ownedPortalPairs) {
            val entry = CompoundTag()
            entry.putUUID("owner", owner)
            entry.putString("first_dim", pair.first.dimensionId)
            entry.putLong("first_pos", pair.first.pos.asLong())
            entry.putString("second_dim", pair.second.dimensionId)
            entry.putLong("second_pos", pair.second.pos.asLong())
            list.add(entry)
        }
        tag.put("portal_pairs", list)
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
            val list = tag.getList("portal_pairs", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                val data = entry as? CompoundTag ?: continue
                if (!data.hasUUID("owner")) {
                    continue
                }

                val owner = data.getUUID("owner")
                val first = PortalEndpoint(
                    data.getString("first_dim"),
                    BlockPos.of(data.getLong("first_pos"))
                )
                val second = PortalEndpoint(
                    data.getString("second_dim"),
                    BlockPos.of(data.getLong("second_pos"))
                )
                out.ownedPortalPairs[owner] = PortalPair(first, second)
            }
            return out
        }
    }
}