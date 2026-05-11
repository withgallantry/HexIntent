package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.ManifestationNetworking
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.block.SplinterCasterBlock
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.entity.FakePlayer
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import java.util.UUID

object SplinterRuntime {
    data class PendingSummon(
        val mediaCost: Long,
        val splinterId: UUID,
        val summonPosition: Vec3,
        val record: SplinterStateStore.SplinterRecord,
        val replacedSplinterId: UUID?
    )

    private data class OwnerPerfState(
        val avgExecMs: Double,
        val breachCount: Int
    )

    private val dirtyOwners: MutableSet<UUID> = mutableSetOf()
    private val ownerPerfStates: MutableMap<UUID, OwnerPerfState> = mutableMapOf()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            tick(server)
        })

        ServerPlayerEvents.AFTER_RESPAWN.register(ServerPlayerEvents.AfterRespawn { _, newPlayer, _ ->
            removeAll(newPlayer.server, newPlayer.uuid)
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping { _ ->
            dirtyOwners.clear()
            ownerPerfStates.clear()
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
        return prepareSummonInternal(env, caster, position, delayTicks, payload, sourceImage, null, null)
    }

    fun prepareAnchoredSummon(
        env: CastingEnvironment,
        caster: ServerPlayer,
        position: Vec3,
        delayTicks: Long,
        payload: List<Iota>,
        sourceImage: CastingImage,
        anchorPosition: Vec3,
        circleImpetusPos: Vec3? = null
    ): PendingSummon {
        return prepareSummonInternal(
            env,
            caster,
            position,
            delayTicks,
            payload,
            sourceImage,
            anchorPosition,
            circleImpetusPos
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

        return prepareSummonInternal(
            env,
            caster,
            position,
            delayTicks,
            payload,
            sourceImage,
            source.anchorPosition,
            source.circleImpetusPos
        )
    }

    fun circleOwnerId(dimensionId: String, blockPos: BlockPos): UUID {
        val raw = "manifestation:circle:$dimensionId:${blockPos.x},${blockPos.y},${blockPos.z}"
        return UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8))
    }

    fun circleCasterFor(level: ServerLevel, ownerId: UUID): ServerPlayer {
        return FakePlayer.get(level, GameProfile(ownerId, CIRCLE_CASTER_NAME))
    }

    fun hasAnchoredSplinterAt(server: MinecraftServer, dimensionId: String, blockPos: BlockPos): Boolean {
        val store = SplinterStateStore.get(server)
        for (owner in store.allOwners()) {
            val records = store.allByOwner(owner)
            for (record in records) {
                if (record.dimensionId != dimensionId) {
                    continue
                }
                val anchor = record.anchorPosition ?: continue
                val anchorBlock = BlockPos.containing(anchor.x, anchor.y - 1.0, anchor.z)
                if (anchorBlock == blockPos) {
                    return true
                }
            }
        }
        return false
    }

    fun removeAnchoredAt(server: MinecraftServer, dimensionId: String, blockPos: BlockPos): Int {
        val store = SplinterStateStore.get(server)
        val owners = store.allOwners().toList()
        var removedCount = 0

        for (owner in owners) {
            val records = store.allByOwner(owner)
            var removedForOwner = false

            for (record in records) {
                if (record.dimensionId != dimensionId) {
                    continue
                }
                val anchor = record.anchorPosition ?: continue
                val anchorBlock = BlockPos.containing(anchor.x, anchor.y - 1.0, anchor.z)
                if (anchorBlock != blockPos) {
                    continue
                }

                if (store.remove(owner, record.id) != null) {
                    removedCount++
                    removedForOwner = true
                }
            }

            if (removedForOwner) {
                markOwnerDirty(owner)
            }
        }

        if (removedCount > 0) {
            flushSnapshots(server)
        }
        return removedCount
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
        ownerPerfStates.remove(owner)
    }

    private fun prepareSummonInternal(
        env: CastingEnvironment,
        caster: ServerPlayer,
        position: Vec3,
        delayTicks: Long,
        payload: List<Iota>,
        sourceImage: CastingImage,
        anchorPosition: Vec3?,
        circleImpetusPos: Vec3?
    ): PendingSummon {
        val store = SplinterStateStore.get(caster.server)
        val owner = caster.uuid
        if (payload.size > MAX_PAYLOAD_IOTAS) {
            throw IllegalArgumentException("payload_too_large")
        }
        val sourceSplinterId = (env as? SplinterCastEnv)?.sourceSplinterId
        val summonPos = position
        val castAt = caster.serverLevel().gameTime + delayTicks.coerceAtLeast(0L)
        var resolvedAnchorPosition = anchorPosition
        var resolvedCircleImpetusPos = circleImpetusPos

        val mediaCost: Long = if (sourceSplinterId == null) {
            val current = store.count(owner)
            val maxActive = ManifestationConfig.splinterMaxActivePerOwner()
            if (maxActive >= 0 && current >= maxActive) {
                throw IllegalStateException("too_many_splinters")
            }
            SPLINTER_SUMMON_DUST_COST * (current + 1L) * MediaConstants.DUST_UNIT
        } else {
            val source = store.get(owner, sourceSplinterId)
                ?: throw IllegalStateException("missing_source_splinter")

            if (resolvedAnchorPosition == null) {
                resolvedAnchorPosition = source.anchorPosition
            }
            if (resolvedCircleImpetusPos == null) {
                resolvedCircleImpetusPos = source.circleImpetusPos
            }

            if (source.anchorPosition != null
                && source.anchorPosition!!.distanceToSqr(summonPos) > SAME_POSITION_EPSILON
            ) {
                throw IllegalStateException("anchored_relocation")
            }
            val sameDim = source.dimensionId == caster.serverLevel().dimension().location().toString()
            val samePos = source.position.distanceToSqr(summonPos) <= SAME_POSITION_EPSILON
            if (sameDim && samePos) 0L else SPLINTER_MOVE_MEDIA_COST
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
            position = summonPos,
            anchorPosition = resolvedAnchorPosition,
            circleImpetusPos = resolvedCircleImpetusPos,
            castAtGameTime = castAt,
            castingHand = env.castingHand,
            payloadTags = payloadTags,
            ravenmindTag = inheritedRavenmind
        )

        return PendingSummon(
            mediaCost = mediaCost,
            splinterId = record.id,
            summonPosition = summonPos,
            record = record,
            replacedSplinterId = sourceSplinterId
        )
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
            val records = store.allByOwner(owner)

            for (record in records) {
                if (executedThisTick >= MAX_EXECUTIONS_PER_TICK) {
                    break
                }

                val isCircleOwned = record.circleImpetusPos != null
                if (!isCircleOwned && player == null) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                    continue
                }

                if (!isCircleOwned && record.dimensionId != player!!.serverLevel().dimension().location().toString()) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                    continue
                }

                val level = levelForRecord(server, record)
                if (level == null) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                    continue
                }

                val anchorPos = record.anchorPosition
                if (anchorPos != null) {
                    val anchorBlock = BlockPos.containing(anchorPos.x, anchorPos.y - 1.0, anchorPos.z)
                    if (level.getBlockState(anchorBlock).block !is SplinterCasterBlock) {
                        store.remove(owner, record.id)
                        markOwnerDirty(owner)
                        continue
                    }
                }

                if (level.gameTime < record.castAtGameTime) {
                    continue
                }

                val startTime = System.nanoTime()
                try {
                    executeSplinter(server, owner, record)
                } catch (mishap: Mishap) {
                    // Circle mishaps are expected failure states; treat them as a completed splinter.
                    showCircleMishapAtImpetus(server, record)
                    showCircleMishapReason(server, record, mishap)
                } catch (e: Throwable) {
                    Manifestation.LOGGER.warn(
                        "Manifestation: splinter {} execution failed unexpectedly.",
                        record.id,
                        e
                    )
                }
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

                if (updatePerformanceStateAndShouldThrottle(owner, elapsedMs)) {
                    Manifestation.LOGGER.warn(
                        "Manifestation: throttling splinters for owner {} due to repeated heavy execution (avg={} ms)",
                        owner,
                        ownerPerfStates[owner]?.avgExecMs ?: elapsedMs
                    )
                    if (store.removeOwner(owner)) {
                        markOwnerDirty(owner)
                    }
                    ownerPerfStates.remove(owner)
                    player?.displayClientMessage(
                        Component.literal("Your splinters were dispelled: repeated heavy execution tripped server protection."),
                        false
                    )
                    break
                }

                executedThisTick++

                if (store.get(owner, record.id) != null) {
                    store.remove(owner, record.id)
                    markOwnerDirty(owner)
                }
            }

            if (store.count(owner) == 0) {
                ownerPerfStates.remove(owner)
            }
        }

        flushSnapshots(server)
    }

    private fun updatePerformanceStateAndShouldThrottle(owner: UUID, elapsedMs: Double): Boolean {
        val previous = ownerPerfStates[owner]
        val nextAvg = if (previous == null) {
            elapsedMs
        } else {
            previous.avgExecMs + PERF_MOVING_AVG_ALPHA * (elapsedMs - previous.avgExecMs)
        }

        val breaches = if (nextAvg > ManifestationConfig.splinterWatchdogMaxAvgExecMs()) {
            (previous?.breachCount ?: 0) + 1
        } else {
            0
        }

        ownerPerfStates[owner] = OwnerPerfState(nextAvg, breaches)
        return breaches >= ManifestationConfig.splinterWatchdogMaxBreaches()
    }

    private fun executeSplinter(server: MinecraftServer, ownerId: UUID, record: SplinterStateStore.SplinterRecord) {
        val level = levelForRecord(server, record)
        if (level == null) {
            Manifestation.LOGGER.warn(
                "Manifestation: splinter {} could not execute due to missing dimension {}",
                record.id,
                record.dimensionId
            )
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

        val owner = if (record.circleImpetusPos != null) {
            circleCasterFor(level, ownerId)
        } else {
            val online = server.playerList.getPlayer(ownerId)
            if (online == null) {
                return
            }
            online
        }

        val env = if (record.circleImpetusPos != null) {
            CircleSplinterCastEnv(
                owner,
                record.castingHand,
                record.position,
                record.id,
                BlockPos.containing(record.circleImpetusPos!!.x, record.circleImpetusPos!!.y, record.circleImpetusPos!!.z)
            )
        } else {
            SplinterCastEnv(owner, record.castingHand, record.position, record.id)
        }

        val vm = CastingVM.empty(env)
        vm.image = buildStartingImage(record.ravenmindTag)
        vm.queueExecuteAndWrapIotas(payload, level)
    }

    private fun levelForRecord(server: MinecraftServer, record: SplinterStateStore.SplinterRecord): ServerLevel? {
        val levelKey = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation(record.dimensionId)
        )
        return server.getLevel(levelKey)
    }

    private fun showCircleMishapAtImpetus(server: MinecraftServer, record: SplinterStateStore.SplinterRecord) {
        val impetusVec = record.circleImpetusPos ?: return
        val level = levelForRecord(server, record) ?: return
        val impetusPos = BlockPos.containing(impetusVec.x, impetusVec.y, impetusVec.z)
        val bs = level.getBlockState(impetusPos)
        val impetus = level.getBlockEntity(impetusPos) as? BlockEntityAbstractImpetus
        ICircleComponent.sfx(impetusPos, bs, level, impetus, false)
    }

    private fun showCircleMishapReason(server: MinecraftServer, record: SplinterStateStore.SplinterRecord, mishap: Mishap) {
        val impetusVec = record.circleImpetusPos ?: return
        val level = levelForRecord(server, record) ?: return
        val envOwner = circleCasterFor(level, record.owner)
        val env = CircleSplinterCastEnv(
            envOwner,
            record.castingHand,
            record.position,
            record.id,
            BlockPos.containing(impetusVec.x, impetusVec.y, impetusVec.z)
        )
        val msg = mishap.errorMessageWithName(
            env,
            Mishap.Context(null, Component.translatable("block.manifestation.splinter_caster"))
        ) ?: return

        val center = Vec3.atCenterOf(BlockPos.containing(impetusVec.x, impetusVec.y, impetusVec.z))
        val maxDistSq = 32.0 * 32.0
        for (player in level.players()) {
            if (player.position().distanceToSqr(center) <= maxDistSq) {
                player.displayClientMessage(msg, false)
            }
        }
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
    private const val PERF_MOVING_AVG_ALPHA = 0.2
    private const val CIRCLE_CASTER_NAME = "Manifestation Circle"
    const val MAX_PAYLOAD_IOTAS = 512
    const val MAX_EXECUTIONS_PER_TICK = 64
}
