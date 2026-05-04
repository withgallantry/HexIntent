package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.misc.MediaConstants
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.ManifestationNetworking
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.floor

object SplinterRuntime {
    data class PendingSummon(
        val mediaCost: Long,
        val splinterId: UUID,
        val snappedPosition: Vec3,
        val record: SplinterStateStore.SplinterRecord,
        val replacedSplinterId: UUID?
    )

    private val dirtyOwners: MutableSet<UUID> = mutableSetOf()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            tick(server)
        })

        ServerPlayerEvents.AFTER_RESPAWN.register(ServerPlayerEvents.AfterRespawn { _, newPlayer, _ ->
            removeAll(newPlayer.server, newPlayer.uuid)
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping { _ ->
            dirtyOwners.clear()
        })
    }

    fun prepareSummon(
        env: CastingEnvironment,
        caster: ServerPlayer,
        position: Vec3,
        delayTicks: Long,
        payload: List<Iota>,
        sourceImage: CastingImage
    ): PendingSummon {
        val store = SplinterStateStore.get(caster.server)
        val owner = caster.uuid
        if (payload.size > MAX_PAYLOAD_IOTAS) {
            throw IllegalArgumentException("payload_too_large")
        }
        val sourceSplinterId = (env as? SplinterCastEnv)?.sourceSplinterId
        val snappedPos = snapToHalfBlock(position)
        val castAt = caster.serverLevel().gameTime + delayTicks.coerceAtLeast(0L)

        val mediaCost: Long
        if (sourceSplinterId == null) {
            val current = store.count(owner)
            if (current >= MAX_ACTIVE_SPLINTERS_PER_OWNER) {
                throw IllegalStateException("too_many_splinters")
            }
            mediaCost = SPLINTER_SUMMON_DUST_COST * (current + 1L) * MediaConstants.DUST_UNIT
        } else {
            val source = store.get(owner, sourceSplinterId)
                ?: throw IllegalStateException("missing_source_splinter")
            val sameDim = source.dimensionId == caster.serverLevel().dimension().location().toString()
            val samePos = source.position.distanceToSqr(snappedPos) <= SAME_POSITION_EPSILON
            mediaCost = if (sameDim && samePos) 0L else SPLINTER_MOVE_MEDIA_COST
        }

        val payloadTags = payload.mapNotNull { iota ->
            val serialized = IotaType.serialize(iota)
            if (serialized is CompoundTag) serialized.copy() else null
        }.toMutableList()

        val inheritedRavenmind = if (sourceSplinterId != null) {
            extractRavenmind(sourceImage)
        } else {
            null
        }

        val record = SplinterStateStore.SplinterRecord(
            id = UUID.randomUUID(),
            owner = owner,
            dimensionId = caster.serverLevel().dimension().location().toString(),
            position = snappedPos,
            castAtGameTime = castAt,
            castingHand = env.castingHand,
            payloadTags = payloadTags,
            ravenmindTag = inheritedRavenmind
        )

        return PendingSummon(
            mediaCost = mediaCost,
            splinterId = record.id,
            snappedPosition = snappedPos,
            record = record,
            replacedSplinterId = sourceSplinterId
        )
    }

    fun prepareRenew(
        env: SplinterCastEnv,
        caster: ServerPlayer,
        position: Vec3,
        delayTicks: Long,
        sourceImage: CastingImage
    ): PendingSummon {
        val store = SplinterStateStore.get(caster.server)
        val source = store.get(caster.uuid, env.sourceSplinterId)
            ?: throw IllegalStateException("missing_source_splinter")

        val payload = mutableListOf<Iota>()
        for (tag in source.payloadTags) {
            val iota = IotaType.deserialize(tag.copy(), caster.serverLevel()) ?: continue
            payload.add(iota)
        }

        return prepareSummon(env, caster, position, delayTicks, payload, sourceImage)
    }

    fun commitSummon(server: MinecraftServer, pending: PendingSummon) {
        val store = SplinterStateStore.get(server)
        val replacedId = pending.replacedSplinterId
        if (replacedId != null) {
            store.remove(pending.record.owner, replacedId)
        }
        store.put(pending.record)
        markOwnerDirty(pending.record.owner)
    }

    fun removeAll(server: MinecraftServer, owner: UUID) {
        val store = SplinterStateStore.get(server)
        if (store.removeOwner(owner)) {
            markOwnerDirty(owner)
            flushSnapshots(server)
        }
    }

    private fun tick(server: MinecraftServer) {
        val store = SplinterStateStore.get(server)
        val owners = store.allOwners().toList()
        var executedThisTick = 0
        for (owner in owners) {
            if (executedThisTick >= MAX_EXECUTIONS_PER_TICK) {
                break
            }
            val player = server.playerList.getPlayer(owner)
            if (player == null) {
                if (store.removeOwner(owner)) {
                    markOwnerDirty(owner)
                }
                continue
            }

            val records = store.allByOwner(owner)
            for (record in records) {
                if (executedThisTick >= MAX_EXECUTIONS_PER_TICK) {
                    break
                }
                if (record.dimensionId != player.serverLevel().dimension().location().toString()) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                    continue
                }

                if (player.serverLevel().gameTime < record.castAtGameTime) {
                    continue
                }

                executeSplinter(server, player, record)
                executedThisTick++

                if (store.get(owner, record.id) != null) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                }
            }
        }

        flushSnapshots(server)
    }

    private fun executeSplinter(server: MinecraftServer, owner: ServerPlayer, record: SplinterStateStore.SplinterRecord) {
        val levelKey = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation(record.dimensionId))
        val level = server.getLevel(levelKey)
        if (level == null) {
            Manifestation.LOGGER.warn("Manifestation: splinter {} could not execute due to missing dimension {}", record.id, record.dimensionId)
            return
        }

        val payload = mutableListOf<Iota>()
        for (tag in record.payloadTags) {
            try {
                val iota = IotaType.deserialize(tag.copy(), level)
                if (iota != null) {
                    payload.add(iota)
                }
            } catch (_: Throwable) {
                // Skip corrupt iotas and continue.
            }
        }

        if (payload.isEmpty()) {
            return
        }

        val env = SplinterCastEnv(owner, record.castingHand, record.position, record.id)
        val vm = CastingVM.empty(env)
        vm.image = buildStartingImage(record.ravenmindTag)

        vm.queueExecuteAndWrapIotas(payload, level)
    }

    private fun markOwnerDirty(owner: UUID) {
        dirtyOwners.add(owner)
    }

    private fun flushSnapshots(server: MinecraftServer) {
        if (dirtyOwners.isEmpty()) {
            return
        }

        val store = SplinterStateStore.get(server)
        val owners = dirtyOwners.toList()
        dirtyOwners.clear()

        for (owner in owners) {
            val player = server.playerList.getPlayer(owner) ?: continue
            val records = store.allByOwner(owner)
            val buf = PacketByteBufs.create()
            buf.writeVarInt(records.size)
            for (record in records) {
                buf.writeUUID(record.id)
                buf.writeUtf(record.dimensionId)
                buf.writeDouble(record.position.x)
                buf.writeDouble(record.position.y)
                buf.writeDouble(record.position.z)
                buf.writeLong(record.castAtGameTime)
            }
            ServerPlayNetworking.send(player, ManifestationNetworking.SPLINTER_SNAPSHOT_S2C, buf)
        }
    }

    private fun snapToHalfBlock(vec: Vec3): Vec3 {
        fun snap(v: Double): Double = floor(v * 2.0 + 0.5) / 2.0
        return Vec3(snap(vec.x), snap(vec.y), snap(vec.z))
    }

    private fun extractRavenmind(image: CastingImage): CompoundTag? {
        if (!image.userData.contains(HexAPI.RAVENMIND_USERDATA)) {
            return null
        }
        return image.userData.getCompound(HexAPI.RAVENMIND_USERDATA).copy()
    }

    private fun buildStartingImage(ravenmind: CompoundTag?): CastingImage {
        if (ravenmind == null) {
            return CastingImage()
        }

        val userData = CompoundTag()
        userData.put(HexAPI.RAVENMIND_USERDATA, ravenmind.copy())
        return CastingImage().copy(userData = userData)
    }

    private const val SAME_POSITION_EPSILON = 1.0e-8
    private const val SPLINTER_SUMMON_DUST_COST = 5L
    private const val SPLINTER_MOVE_MEDIA_COST = MediaConstants.DUST_UNIT / 2L
    const val MAX_ACTIVE_SPLINTERS_PER_OWNER = 64
    const val MAX_PAYLOAD_IOTAS = 512
    const val MAX_EXECUTIONS_PER_TICK = 64
}
