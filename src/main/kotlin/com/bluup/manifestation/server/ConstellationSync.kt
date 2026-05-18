package com.bluup.manifestation.server

import com.bluup.manifestation.common.ManifestationNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

object ConstellationSync {
    /**
     * Send all enabled constellations to the given player.
     * Only runs if the feature is enabled in config.
     */
    fun sendSnapshotTo(player: ServerPlayer) {
        if (!ManifestationConfig.constellationFeatureEnabled()) return
        val server = player.server ?: return
        val store = ConstellationStateStore.get(server)
        val constellations = store.all().filter { it.enabled }
        val buf = PacketByteBufs.create()
        buf.writeVarInt(constellations.size)
        for (c in constellations) {
            buf.writeUUID(c.owner)
            buf.writeInt(c.color)
            buf.writeVarInt(c.stars.size)
            for (s in c.stars) {
                buf.writeDouble(s.x)
                buf.writeDouble(s.y)
                buf.writeDouble(s.z)
            }
            buf.writeVarInt(c.edges.size)
            for (e in c.edges) {
                buf.writeVarInt(e.from)
                buf.writeVarInt(e.to)
            }
        }
        ServerPlayNetworking.send(player, ManifestationNetworking.CONSTELLATION_SNAPSHOT_S2C, buf)
    }

    /**
     * Broadcast to all players in the server.
     */
    fun broadcastSnapshot(server: MinecraftServer) {
        if (!ManifestationConfig.constellationFeatureEnabled()) return
        for (player in server.playerList.players) {
            sendSnapshotTo(player)
        }
    }
}
