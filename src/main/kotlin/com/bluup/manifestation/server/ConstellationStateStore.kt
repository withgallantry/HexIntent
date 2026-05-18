package com.bluup.manifestation.server

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

class ConstellationStateStore : SavedData() {
    data class Star(val x: Double, val y: Double, val z: Double)
    data class Edge(val from: Int, val to: Int)
    data class Constellation(
        val owner: UUID,
        val color: Int, // 0xRRGGBB
        val stars: List<Star>,
        val edges: List<Edge>,
        var enabled: Boolean = true
    )

    private val constellations: MutableMap<UUID, Constellation> = mutableMapOf()

    fun get(owner: UUID): Constellation? = constellations[owner]
    fun put(constellation: Constellation) {
        constellations[constellation.owner] = constellation
        setDirty()
    }
    fun remove(owner: UUID) {
        if (constellations.remove(owner) != null) setDirty()
    }
    fun all(): Collection<Constellation> = constellations.values

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (c in constellations.values) {
            val t = CompoundTag()
            t.putUUID("owner", c.owner)
            t.putInt("color", c.color)
            t.putBoolean("enabled", c.enabled)
            val stars = ListTag()
            for (s in c.stars) {
                val st = CompoundTag()
                st.putDouble("x", s.x)
                st.putDouble("y", s.y)
                st.putDouble("z", s.z)
                stars.add(st)
            }
            t.put("stars", stars)
            val edges = ListTag()
            for (e in c.edges) {
                val et = CompoundTag()
                et.putInt("from", e.from)
                et.putInt("to", e.to)
                edges.add(et)
            }
            t.put("edges", edges)
            list.add(t)
        }
        tag.put("constellations", list)
        return tag
    }

    companion object {
        private const val DATA_NAME = "manifestation_constellations"
        fun get(server: MinecraftServer): ConstellationStateStore {
            return server.overworld().dataStorage.computeIfAbsent(
                ::load, ::ConstellationStateStore, DATA_NAME
            )
        }
        private fun load(tag: CompoundTag): ConstellationStateStore {
            val out = ConstellationStateStore()
            val list = tag.getList("constellations", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                entry as CompoundTag
                val owner = entry.getUUID("owner")
                val color = entry.getInt("color")
                val enabled = entry.getBoolean("enabled")
                val stars = mutableListOf<Star>()
                val starList = entry.getList("stars", Tag.TAG_COMPOUND.toInt())
                for (s in starList) {
                    s as CompoundTag
                    stars.add(Star(s.getDouble("x"), s.getDouble("y"), s.getDouble("z")))
                }
                val edges = mutableListOf<Edge>()
                val edgeList = entry.getList("edges", Tag.TAG_COMPOUND.toInt())
                for (e in edgeList) {
                    e as CompoundTag
                    edges.add(Edge(e.getInt("from"), e.getInt("to")))
                }
                out.constellations[owner] = Constellation(owner, color, stars, edges, enabled)
            }
            return out
        }
    }
}
