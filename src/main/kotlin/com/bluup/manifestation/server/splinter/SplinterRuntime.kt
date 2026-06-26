package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import at.petrak.hexcasting.api.casting.circles.ICircleComponent
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame
import at.petrak.hexcasting.api.casting.eval.vm.FrameForEach
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.casting.mishaps.MishapOthersName
import at.petrak.hexcasting.api.misc.MediaConstants
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.ManifestationNetworking
import com.bluup.manifestation.server.KotlinNbtCompat
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
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import java.util.UUID

object SplinterRuntime {
    private sealed class ForEachConversionResult {
        object NoConversion : ForEachConversionResult()
        data class Converted(
            val continuationTags: MutableList<CompoundTag>
        ) : ForEachConversionResult()
        data class Failed(
            val reason: String
        ) : ForEachConversionResult()
    }

    data class PendingSummon(
        val mediaCost: Long,
        val splinterId: UUID,
        val summonPosition: Vec3,
        val record: SplinterStateStore.SplinterRecord,
        val replacedSplinterId: UUID?
    )

    private val dirtyOwners: MutableSet<UUID> = mutableSetOf()
    private val ownerRecordCursors: MutableMap<UUID, Int> = mutableMapOf()
    private var ownerRoundRobinCursor: Int = 0

    private const val SAME_POSITION_EPSILON = 1.0e-8
    private const val SPLINTER_SUMMON_DUST_COST = 5L
    private const val SPLINTER_MOVE_MEDIA_COST = MediaConstants.DUST_UNIT / 500L
    private const val CIRCLE_CASTER_NAME = "Manifestation Circle"
    private const val LARGE_LIST_STALE_CLEANUP_TICKS = 20L * 60L * 10L

    internal fun applyCastResultToVm(
        vm: CastingVM,
        env: CastingEnvironment,
        result: CastResult
    ): SpellContinuation {
        result.newData?.let { vm.image = it }
        env.postExecution(result)
        vm.performSideEffects(result.sideEffects)
        return result.continuation
    }

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
            tick(server)
        })

        ServerPlayerEvents.AFTER_RESPAWN.register(ServerPlayerEvents.AfterRespawn { _, newPlayer, _ ->
            removeAll(newPlayer.server, newPlayer.uuid)
        })

        ServerLifecycleEvents.SERVER_STOPPING.register(ServerLifecycleEvents.ServerStopping { _ ->
            dirtyOwners.clear()
            ownerRecordCursors.clear()
            ownerRoundRobinCursor = 0
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

    fun requestPostCompletionRenew(
        env: SplinterCastEnv,
        caster: ServerPlayer,
        position: Vec3,
        delayTicks: Long,
        sourceImage: CastingImage
    ): Long {
        val store = SplinterStateStore.get(caster.server)
        val source = store.get(caster.uuid, env.sourceSplinterId)
            ?: throw IllegalStateException("missing_source_splinter")

        if (source.anchorPosition != null
            && source.anchorPosition!!.distanceToSqr(position) > SAME_POSITION_EPSILON
        ) {
            throw IllegalStateException("anchored_relocation")
        }

        val sameDim = source.dimensionId == caster.serverLevel().dimension().location().toString()
        val samePos = source.position.distanceToSqr(position) <= SAME_POSITION_EPSILON
        val currentPendingPos = source.pendingRenewPosition
        val pendingAlreadyMoves = currentPendingPos != null &&
            !(sameDim && source.position.distanceToSqr(currentPendingPos) <= SAME_POSITION_EPSILON)
        val mediaCost = if (!pendingAlreadyMoves && !(sameDim && samePos)) SPLINTER_MOVE_MEDIA_COST else 0L

        validateRavenmindForPersistence(extractRavenmind(sourceImage), caster)

        source.pendingRenewPosition = position
        source.pendingRenewDelayTicks = delayTicks.coerceAtLeast(0L)
        source.pendingRenewRequestedGameTime = caster.serverLevel().gameTime
        store.markDirty()
        markOwnerDirty(source.owner)

        maybeLogRenewRequested(source, position, source.pendingRenewDelayTicks!!)

        return mediaCost
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

                if (removeRecord(store, owner, record)) {
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
            val replaced = store.get(pending.record.owner, replacedId)
            if (replaced != null) {
                val unfinished = replaced.started || replaced.continuationTags.isNotEmpty() || replaced.imageTag != null
                if (unfinished && ManifestationConfig.splinterDebugSliceTelemetry()) {
                    Manifestation.LOGGER.warn(
                        "Manifestation splinter immediate replacement of unfinished record: replacedId={}, newId={}, owner={}, started={}, continuationTags={}, lastFrames={}, lastStack={}",
                        replaced.id,
                        pending.record.id,
                        replaced.owner,
                        replaced.started,
                        replaced.continuationTags.size,
                        replaced.lastObservedContinuationSize,
                        replaced.lastObservedStackSize
                    )
                }
                removeRecord(store, pending.record.owner, replaced)
            }
        }
        store.put(pending.record)
        markOwnerDirty(pending.record.owner)
    }

    fun removeAll(server: MinecraftServer, owner: UUID) {
        val store = SplinterStateStore.get(server)
        val removedRecords = store.allByOwner(owner)
        if (store.removeOwner(owner)) {
            for (record in removedRecords) {
                store.deleteLargeListsForSplinter(record.id)
            }
            markOwnerDirty(owner)
            flushSnapshots(server)
        }
        ownerRecordCursors.remove(owner)
    }

    fun remove(server: MinecraftServer, owner: UUID, splinterId: UUID): Boolean {
        val store = SplinterStateStore.get(server)
        val record = store.get(owner, splinterId) ?: return false
        if (!removeRecord(store, owner, record)) {
            return false
        }

        if (store.count(owner) <= 0) {
            ownerRecordCursors.remove(owner)
        }
        flushSnapshots(server)
        return true
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
        if (IotaType.isTooLargeToSerialize(payload)) {
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
            throwIfOtherTrueNameEmbedded(iota, caster)
            val serialized = IotaType.serialize(iota)
            if (serialized is CompoundTag) serialized.copy() else null
        }.toMutableList()

        val inheritedRavenmind = if (sourceSplinterId != null) {
            extractRavenmind(sourceImage)
        } else {
            null
        }
        validateRavenmindForPersistence(inheritedRavenmind, caster)

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
            ravenmindTag = inheritedRavenmind,
            started = false,
            imageTag = null,
            continuationTags = mutableListOf(),
            createdGameTime = castAt,
            lastRunGameTime = castAt,
            lastProgressGameTime = castAt,
            totalHexOpsRun = 0L,
            totalFrameStepsRun = 0L,
            overBudgetCount = 0,
            lastObservedStackSize = null,
            lastObservedContinuationSize = null
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
        ManifestationConfig.maybeLogResolvedSplinterTuningOnce()
        val store = SplinterStateStore.get(server)
        val owners = store.allOwners().toList()
        val ownersSet = owners.toSet()
        ownerRecordCursors.keys.retainAll(ownersSet)
        val activeSplinterIds = owners
            .flatMap { owner -> store.allByOwner(owner).map { it.id } }
            .toSet()
        store.cleanupOrphanedLargeLists(
            activeSplinterIds,
            server.overworld().gameTime,
            LARGE_LIST_STALE_CLEANUP_TICKS
        )

        if (owners.isEmpty()) {
            ownerRoundRobinCursor = 0
            flushSnapshots(server)
            return
        }

        val globalBudgetNanos = ManifestationConfig.splinterGlobalBudgetMicrosPerTick().coerceAtLeast(1L) * 1_000L
        val opsPerSlice = ManifestationConfig.splinterOpsPerSlice().coerceAtLeast(1)
        val maxSlicesThisTick = ManifestationConfig.splinterMaxSlicesPerTick().coerceAtLeast(1)
        val maxRecordsScannedThisTick = ManifestationConfig.splinterMaxRecordScansPerTick()
            .coerceAtLeast(maxSlicesThisTick)
        val emergencySliceNanos = ManifestationConfig.splinterEmergencySliceMillis().coerceAtLeast(1L) * 1_000_000L

        val tickStartNanos = System.nanoTime()
        fun budgetRemaining(): Boolean = (System.nanoTime() - tickStartNanos) < globalBudgetNanos

        val startOwnerIndex = ownerRoundRobinCursor.mod(owners.size)
        var ownersVisited = 0
        var slicesThisTick = 0
        var scannedThisTick = 0

        while (
            ownersVisited < owners.size &&
            slicesThisTick < maxSlicesThisTick &&
            scannedThisTick < maxRecordsScannedThisTick &&
            budgetRemaining()
        ) {
            val ownerIndex = (startOwnerIndex + ownersVisited) % owners.size
            val owner = owners[ownerIndex]
            ownersVisited++

            val player = server.playerList.getPlayer(owner)
            val records = store.allByOwner(owner)
            if (records.isEmpty()) {
                ownerRecordCursors.remove(owner)
                continue
            }

            val startRecordIndex = ownerRecordCursors[owner]?.let { cursor ->
                cursor.mod(records.size)
            } ?: 0
            var inspectedForOwner = 0
            var ranSliceForOwner = false

            while (
                inspectedForOwner < records.size &&
                !ranSliceForOwner &&
                slicesThisTick < maxSlicesThisTick &&
                scannedThisTick < maxRecordsScannedThisTick &&
                budgetRemaining()
            ) {
                val recordIndex = (startRecordIndex + inspectedForOwner) % records.size
                val record = records[recordIndex]
                inspectedForOwner++
                scannedThisTick++

                val isCircleOwned = record.circleImpetusPos != null
                if (!isCircleOwned && player == null) {
                    removeRecord(store, owner, record)
                    continue
                }

                if (!isCircleOwned && record.dimensionId != player!!.serverLevel().dimension().location().toString()) {
                    removeRecord(store, owner, record)
                    continue
                }

                val level = levelForRecord(server, record)
                if (level == null) {
                    removeRecord(store, owner, record)
                    continue
                }

                val anchorPos = record.anchorPosition
                if (anchorPos != null) {
                    val anchorBlock = BlockPos.containing(anchorPos.x, anchorPos.y - 1.0, anchorPos.z)
                    if (!ManifestationConfig.splinterCasterEnabled() || level.getBlockState(anchorBlock).block !is SplinterCasterBlock) {
                        removeRecord(store, owner, record)
                        continue
                    }
                }

                if (!hasValidCircleImpetus(level, record)) {
                    removeRecord(store, owner, record)
                    continue
                }

                if (level.gameTime < record.castAtGameTime) {
                    continue
                }

                val earlyKillReason = splinterKillReason(record, level)
                if (earlyKillReason != null) {
                    removeRecord(store, owner, record)
                    maybeLogSliceTelemetry(
                        record = record,
                        result = null,
                        elapsedNanos = 0L,
                        overBudgetBefore = record.overBudgetCount,
                        overBudgetAfter = record.overBudgetCount,
                        killReason = earlyKillReason,
                        didRunSlice = false
                    )
                    notifyOwnerDispelled(server, record, earlyKillReason)
                    continue
                }

                val sliceStartNanos = System.nanoTime()
                var touchedInPlace = false
                var result: SliceResult? = null

                try {
                    result = executeSplinterSlice(server, owner, record, opsPerSlice)
                    touchedInPlace = result.status == SliceStatus.UNFINISHED
                } catch (mishap: Mishap) {
                    if (record.circleImpetusPos != null) {
                        showCircleMishapAtImpetus(server, record)
                        showCircleMishapReason(server, record, mishap)
                    }
                    result = SliceResult(
                        status = SliceStatus.REMOVE,
                        didProgress = false,
                        stepsRun = 0,
                        beforeOps = 0L,
                        afterOps = 0L,
                        beforeStackSize = record.lastObservedStackSize ?: 0,
                        afterStackSize = record.lastObservedStackSize ?: 0,
                        beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        afterContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        completed = false
                    )
                } catch (e: Throwable) {
                    Manifestation.LOGGER.warn(
                        "Manifestation: splinter {} slice failed unexpectedly.",
                        record.id,
                        e
                    )
                    result = SliceResult(
                        status = SliceStatus.REMOVE,
                        didProgress = false,
                        stepsRun = 0,
                        beforeOps = 0L,
                        afterOps = 0L,
                        beforeStackSize = record.lastObservedStackSize ?: 0,
                        afterStackSize = record.lastObservedStackSize ?: 0,
                        beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        afterContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        completed = false
                    )
                }

                val elapsedNanos = System.nanoTime() - sliceStartNanos
                val overBudgetBefore = record.overBudgetCount
                record.overBudgetCount = if (elapsedNanos > emergencySliceNanos) {
                    record.overBudgetCount + 1
                } else {
                    0
                }
                touchedInPlace = touchedInPlace || record.overBudgetCount != overBudgetBefore

                val postSliceKillReason = splinterKillReason(record, level)
                maybeLogSliceTelemetry(
                    record = record,
                    result = result,
                    elapsedNanos = elapsedNanos,
                    overBudgetBefore = overBudgetBefore,
                    overBudgetAfter = record.overBudgetCount,
                    killReason = postSliceKillReason,
                    didRunSlice = true
                )
                if (postSliceKillReason != null) {
                    removeRecord(store, owner, record)
                    notifyOwnerDispelled(server, record, postSliceKillReason)
                    slicesThisTick++
                    ranSliceForOwner = true
                    continue
                }

                when (result.status) {
                    SliceStatus.DONE -> {
                        if (ManifestationConfig.splinterDebugSliceTelemetry()) {
                            Manifestation.LOGGER.info(
                                "Splinter renew completion check: source={}, owner={}, started={}, imagePresent={}, continuationTags={}, resultCompleted={}, pendingRenew={}, afterFrames={}, afterStack={}, afterOps={}, recordObservedFrames={}, recordObservedStack={}",
                                record.id,
                                record.owner,
                                record.started,
                                record.imageTag != null,
                                record.continuationTags.size,
                                result.completed,
                                record.pendingRenewPosition != null,
                                result.afterContinuationFrameCount,
                                result.afterStackSize,
                                result.afterOps,
                                record.lastObservedContinuationSize ?: 0,
                                record.lastObservedStackSize ?: 0
                            )
                        }

                        val renewPosition = record.pendingRenewPosition
                        val renewDelay = record.pendingRenewDelayTicks

                        removeRecord(store, owner, record)

                        if (result.completed && renewPosition != null && renewDelay != null) {
                            val renewed = createRenewedRecordAfterCompletion(
                                source = record,
                                level = level,
                                renewPosition = renewPosition,
                                delayTicks = renewDelay
                            )
                            store.put(renewed)
                            markOwnerDirty(renewed.owner)
                            maybeLogRenewCommitted(source = record, renewed = renewed)
                        }
                    }

                    SliceStatus.REMOVE -> {
                        removeRecord(store, owner, record)
                    }

                    SliceStatus.UNFINISHED -> {
                        if (touchedInPlace || result.didProgress) {
                            store.markDirty()
                            markOwnerDirty(owner)
                        }
                    }
                }

                slicesThisTick++
                ranSliceForOwner = true
            }

            val remainingForOwner = store.count(owner)
            if (remainingForOwner == 0) {
                ownerRecordCursors.remove(owner)
            } else {
                ownerRecordCursors[owner] = (startRecordIndex + inspectedForOwner).mod(remainingForOwner)
            }
        }

        ownerRoundRobinCursor = (startOwnerIndex + ownersVisited).mod(owners.size)
        flushSnapshots(server)
    }

    private fun splinterKillReason(record: SplinterStateStore.SplinterRecord, level: ServerLevel): String? {
        val gameTime = level.gameTime
        if (gameTime - record.createdGameTime > ManifestationConfig.splinterMaxLifetimeTicks()) {
            return "it exceeded maximum lifetime"
        }
        if (gameTime - record.lastProgressGameTime > ManifestationConfig.splinterMaxNoProgressTicks()) {
            return "it made no progress for too long"
        }
        val totalWorkUnits = maxOf(record.totalHexOpsRun, record.totalFrameStepsRun)
        if (totalWorkUnits > ManifestationConfig.splinterMaxTotalWorkUnits()) {
            return "it exceeded maximum total work"
        }
        if (record.overBudgetCount >= ManifestationConfig.splinterMaxOverBudgetBreaches()) {
            return "it repeatedly exceeded emergency execution time"
        }
        return null
    }

    private fun notifyOwnerDispelled(server: MinecraftServer, record: SplinterStateStore.SplinterRecord, reason: String) {
        val ownerPlayer = server.playerList.getPlayer(record.owner) ?: return
        ownerPlayer.displayClientMessage(
            Component.literal("Your splinter was dispelled because $reason."),
            false
        )
    }

    private fun removeRecord(
        store: SplinterStateStore,
        owner: UUID,
        record: SplinterStateStore.SplinterRecord
    ): Boolean {
        val removed = store.remove(owner, record.id)
        if (removed != null) {
            store.deleteLargeListsForSplinter(record.id)
            markOwnerDirty(owner)
            return true
        }
        return false
    }

    private fun executeSplinterSlice(
        server: MinecraftServer,
        ownerId: UUID,
        record: SplinterStateStore.SplinterRecord,
        opsBudget: Int
    ): SliceResult {
        val level = levelForRecord(server, record)
        if (level == null) {
            Manifestation.LOGGER.warn(
                "Splinter {} could not execute due to missing dimension {}",
                record.id,
                record.dimensionId
            )
            return SliceResult(
                status = SliceStatus.REMOVE,
                didProgress = false,
                stepsRun = 0,
                beforeOps = 0L,
                afterOps = 0L,
                beforeStackSize = record.lastObservedStackSize ?: 0,
                afterStackSize = record.lastObservedStackSize ?: 0,
                beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                afterContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                completed = false
            )
        }

        val owner = if (record.circleImpetusPos != null) {
            circleCasterFor(level, ownerId)
        } else {
            val online = server.playerList.getPlayer(ownerId) ?: return SliceResult(
                status = SliceStatus.REMOVE,
                didProgress = false,
                stepsRun = 0,
                beforeOps = 0L,
                afterOps = 0L,
                beforeStackSize = record.lastObservedStackSize ?: 0,
                afterStackSize = record.lastObservedStackSize ?: 0,
                beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                afterContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                completed = false
            )
            online
        }

        val env = if (record.circleImpetusPos != null) {
            CircleSplinterCastEnv(
                owner,
                record.castingHand,
                record.position,
                record.id,
                BlockPos.containing(record.circleImpetusPos!!.x, record.circleImpetusPos!!.y, record.circleImpetusPos!!.z),
                record.ambitRadius
            )
        } else {
            SplinterCastEnv(owner, record.castingHand, record.position, record.id, record.ambitRadius)
        }

        val startingImage: CastingImage
        var continuation: SpellContinuation

        if (!record.started) {
            val payload = deserializePayload(record, level, owner)
            if (payload.isEmpty()) {
                return SliceResult(
                    status = SliceStatus.DONE,
                    didProgress = false,
                    stepsRun = 0,
                    beforeOps = 0L,
                    afterOps = 0L,
                    beforeStackSize = 0,
                    afterStackSize = 0,
                    beforeContinuationFrameCount = 0,
                    afterContinuationFrameCount = 0,
                    completed = true
                )
            }

            startingImage = buildStartingImage(validatedRavenmindForExecution(record.ravenmindTag, level, owner))
            val initial = buildInitialContinuation(payload)
                ?: return SliceResult(
                    status = SliceStatus.DONE,
                    didProgress = false,
                    stepsRun = 0,
                    beforeOps = startingImage.opsConsumed,
                    afterOps = startingImage.opsConsumed,
                    beforeStackSize = startingImage.stack.size,
                    afterStackSize = startingImage.stack.size,
                    beforeContinuationFrameCount = 0,
                    afterContinuationFrameCount = 0,
                    completed = true
                )
            continuation = initial
            record.started = true
        } else {
            val loadedImage = deserializeImage(record.imageTag, level)
                ?: run {
                    Manifestation.LOGGER.warn(
                        "Splinter {} could not resume because image deserialization failed. owner={}, started={}, imagePresent={}, continuationTags={}, lastFrames={}, lastStack={}",
                        record.id,
                        record.owner,
                        record.started,
                        record.imageTag != null,
                        record.continuationTags.size,
                        record.lastObservedContinuationSize,
                        record.lastObservedStackSize
                    )
                    return SliceResult(
                        status = SliceStatus.REMOVE,
                        didProgress = false,
                        stepsRun = 0,
                        beforeOps = 0L,
                        afterOps = 0L,
                        beforeStackSize = record.lastObservedStackSize ?: 0,
                        afterStackSize = record.lastObservedStackSize ?: 0,
                        beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        afterContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        completed = false
                    )
                }
            val loadedContinuation = deserializeContinuation(record.continuationTags, level)
            if (loadedContinuation == null) {
                if (record.continuationTags.isEmpty() && record.imageTag != null) {
                    Manifestation.LOGGER.warn(
                        "Splinter {} entered inconsistent resume state (started with image but no continuation tags). owner={}, started={}, imagePresent={}, continuationTags={}, lastFrames={}, lastStack={}",
                        record.id,
                        record.owner,
                        record.started,
                        record.imageTag != null,
                        record.continuationTags.size,
                        record.lastObservedContinuationSize,
                        record.lastObservedStackSize
                    )
                    return SliceResult(
                        status = SliceStatus.REMOVE,
                        didProgress = false,
                        stepsRun = 0,
                        beforeOps = loadedImage.opsConsumed,
                        afterOps = loadedImage.opsConsumed,
                        beforeStackSize = loadedImage.stack.size,
                        afterStackSize = loadedImage.stack.size,
                        beforeContinuationFrameCount = record.lastObservedContinuationSize ?: 0,
                        afterContinuationFrameCount = 0,
                        completed = false
                    )
                }

                Manifestation.LOGGER.warn(
                    "Splinter {} could not resume because continuation deserialization failed. owner={}, started={}, imagePresent={}, continuationTags={}, lastFrames={}, lastStack={}",
                    record.id,
                    record.owner,
                    record.started,
                    record.imageTag != null,
                    record.continuationTags.size,
                    record.lastObservedContinuationSize,
                    record.lastObservedStackSize
                )

                return SliceResult(
                    status = SliceStatus.REMOVE,
                    didProgress = false,
                    stepsRun = 0,
                    beforeOps = loadedImage.opsConsumed,
                    afterOps = loadedImage.opsConsumed,
                    beforeStackSize = loadedImage.stack.size,
                    afterStackSize = loadedImage.stack.size,
                    beforeContinuationFrameCount = record.continuationTags.size,
                    afterContinuationFrameCount = 0,
                    completed = false
                )
            }

            startingImage = loadedImage
            continuation = loadedContinuation
        }

        val beforeImageTag = record.imageTag?.copy()
        val beforeContinuationTags = record.continuationTags.map { it.copy() }
        val beforeStackSize = startingImage.stack.size
        val beforeContinuationSize = continuationFrameCount(continuation)
        val beforeOps = startingImage.opsConsumed
        val store = SplinterStateStore.get(server)

        val vm = CastingVM(startingImage, env)
        var steps = 0
        val sliceBudgetNanos = ManifestationConfig.splinterSliceBudgetMicros().coerceAtLeast(1L) * 1_000L
        val evalStartNanos = System.nanoTime()
        val frameContext = ManifestationSplinterFrameContext.Context(
            splinterId = record.id,
            ownerId = record.owner,
            ownerPlayer = owner,
            level = level,
            state = store,
            debugTelemetry = ManifestationConfig.splinterDebugSliceTelemetry(),
            safeInlineCap = ManifestationConfig.splinterSafeInlineForeachRemainingCap().coerceAtLeast(1)
        )

        while (
            steps < opsBudget &&
            continuation is SpellContinuation.NotDone &&
            (System.nanoTime() - evalStartNanos) < sliceBudgetNanos
        ) {
            val notDone = continuation as SpellContinuation.NotDone
            val castResult = ManifestationSplinterFrameContext.withContext(frameContext) {
                notDone.frame.evaluate(notDone.next, level, vm)
            }
            continuation = applyCastResultToVm(vm, env, castResult)
            steps++
        }

        val finalImage = vm.image
        val afterOps = finalImage.opsConsumed
        val opsDelta = (afterOps - beforeOps).coerceAtLeast(0L)
        val completed = continuation !is SpellContinuation.NotDone

        val nextImageTag = if (completed) null else serializeImage(finalImage)
        val nextContinuationTags = if (continuation is SpellContinuation.NotDone) {
            serializeContinuation(continuation)
        } else {
            mutableListOf()
        }

        if (ManifestationConfig.splinterDebugSliceTelemetry()) {
            Manifestation.LOGGER.info(
                "Manifestation splinter persistence: id={}, completed={}, frameClasses={}, tagCount={}, tagTypes={}",
                record.id,
                completed,
                SplinterLargeForEachRunner.continuationFrameClassNames(continuation),
                nextContinuationTags.size,
                SplinterLargeForEachRunner.continuationTagTypes(nextContinuationTags)
            )
        }

        val referencedListIdsBefore = SplinterLargeForEachRunner.collectReferencedLargeListIds(record.continuationTags)

        if (completed) {
            record.imageTag = null
            record.continuationTags.clear()
        } else {
            val conversionResult = convertUnsafeLargeForEachOrNull(
                store = store,
                level = level,
                owner = owner,
                record = record,
                continuation = continuation,
                serializedTags = nextContinuationTags
            )
            val tagsToPersist = when (conversionResult) {
                is ForEachConversionResult.NoConversion -> nextContinuationTags
                is ForEachConversionResult.Converted -> conversionResult.continuationTags
                is ForEachConversionResult.Failed -> {
                    return SliceResult(
                        status = SliceStatus.REMOVE,
                        didProgress = false,
                        stepsRun = steps,
                        beforeOps = beforeOps,
                        afterOps = afterOps,
                        beforeStackSize = beforeStackSize,
                        afterStackSize = finalImage.stack.size,
                        beforeContinuationFrameCount = beforeContinuationSize,
                        afterContinuationFrameCount = continuationFrameCount(continuation),
                        completed = false
                    )
                }
            }

            persistContinuationState(record, nextImageTag, tagsToPersist)
        }

        val persistedContinuationTags = record.continuationTags.map { it.copy() }.toMutableList()

        val referencedListIdsAfter = SplinterLargeForEachRunner.collectReferencedLargeListIds(record.continuationTags)

        for (obsoleteListId in referencedListIdsBefore - referencedListIdsAfter) {
            if (ManifestationConfig.splinterDebugSliceTelemetry()) {
                Manifestation.LOGGER.info(
                    "Manifestation splinter deleting obsolete large list: id={}, listId={}",
                    record.id,
                    obsoleteListId
                )
            }
            store.deleteLargeList(obsoleteListId)
        }

        if (completed) {
            record.started = false
        } else {
            record.started = true
        }

        record.lastRunGameTime = level.gameTime
        record.totalHexOpsRun += opsDelta
        record.totalFrameStepsRun += steps.toLong()

        val continuationSize = continuationFrameCount(continuation)
        val stackSize = finalImage.stack.size
        record.lastObservedStackSize = stackSize
        record.lastObservedContinuationSize = continuationSize

        val structuralProgress = completed ||
            opsDelta > 0L ||
            stackSize != beforeStackSize ||
            continuationSize != beforeContinuationSize
        val nbtProgress = if (structuralProgress) {
            false
        } else {
            !compoundTagsEqual(beforeImageTag, nextImageTag) ||
                !compoundTagListsEqual(beforeContinuationTags, persistedContinuationTags)
        }
        val didProgress = structuralProgress || nbtProgress

        if (didProgress) {
            record.lastProgressGameTime = level.gameTime
        }

        return if (completed) {
            SliceResult(
                status = SliceStatus.DONE,
                didProgress = didProgress,
                stepsRun = steps,
                beforeOps = beforeOps,
                afterOps = afterOps,
                beforeStackSize = beforeStackSize,
                afterStackSize = stackSize,
                beforeContinuationFrameCount = beforeContinuationSize,
                afterContinuationFrameCount = continuationSize,
                completed = true
            )
        } else {
            SliceResult(
                status = SliceStatus.UNFINISHED,
                didProgress = didProgress,
                stepsRun = steps,
                beforeOps = beforeOps,
                afterOps = afterOps,
                beforeStackSize = beforeStackSize,
                afterStackSize = stackSize,
                beforeContinuationFrameCount = beforeContinuationSize,
                afterContinuationFrameCount = continuationSize,
                completed = false
            )
        }
    }

    private fun convertUnsafeLargeForEachOrNull(
        store: SplinterStateStore,
        level: ServerLevel,
        owner: ServerPlayer,
        record: SplinterStateStore.SplinterRecord,
        continuation: SpellContinuation,
        serializedTags: MutableList<CompoundTag>
    ): ForEachConversionResult {
        val safeCap = ManifestationConfig.splinterSafeInlineForeachRemainingCap().coerceAtLeast(1)
        val frameClasses = SplinterLargeForEachRunner.continuationFrameClassNames(continuation)
        val serializedTagTypes = SplinterLargeForEachRunner.continuationTagTypes(serializedTags)

        var current = continuation
        var frameIndex = 0
        var unsafeCount = 0
        var unsafeFrame: FrameForEach? = null
        var unsafeFrameIndex: Int? = null

        while (current is SpellContinuation.NotDone) {
            val frame = current.frame
            if (frame is FrameForEach) {
                val liveRemaining = frame.data.toList().size
                val serializedRemaining = serializedForeachDataSize(serializedTags.getOrNull(frameIndex))
                val unsafe = liveRemaining > safeCap || (liveRemaining > 0 && serializedRemaining == 0)
                if (unsafe) {
                    unsafeCount++
                    unsafeFrame = frame
                    unsafeFrameIndex = frameIndex
                }
            }
            current = current.next
            frameIndex++
        }

        if (unsafeCount == 0) {
            return ForEachConversionResult.NoConversion
        }
        if (!ManifestationConfig.splinterUseExternalizedForEachFrame()) {
            Manifestation.LOGGER.error(
                "Spliter - Unsafe foreach with externalized frames disabled: id={}, unsafeCount={}, frameClasses={}, serializedTagTypes={}",
                record.id,
                unsafeCount,
                frameClasses,
                serializedTagTypes
            )
            return ForEachConversionResult.Failed("externalized_foreach_rollout_disabled")
        }
        if (unsafeCount > 1 || unsafeFrame == null) {
            Manifestation.LOGGER.error(
                "Spliter - Externalized foreach conversion skipped: id={}, unsafeCount={}, frameClasses={}, serializedTagTypes={}",
                record.id,
                unsafeCount,
                frameClasses,
                serializedTagTypes
            )
            return ForEachConversionResult.Failed("multiple_unsafe_foreach_frames")
        }
        val foreachFrame = unsafeFrame
        val resolvedFrameIndex = unsafeFrameIndex ?: return ForEachConversionResult.Failed("multiple_unsafe_foreach_frames")
        val liveRemaining = foreachFrame.data.toList().size

        val inputTags = serializeIotasOrNull(foreachFrame.data.toList(), owner)
            ?: return ForEachConversionResult.Failed("input_serialize_failed")
        val bodyCodeTags = serializeIotasOrNull(foreachFrame.code.toList(), owner)
            ?: return ForEachConversionResult.Failed("body_serialize_failed")
        val baseStackTags = when (val explicitBaseStack = foreachFrame.baseStack) {
            null -> {
                if (resolvedFrameIndex != 0) {
                    Manifestation.LOGGER.warn(
                        "Spliter - Deferring nested unsafe foreach with implicit base stack: id={}, frameIndex={}, liveRemaining={}, frameClasses={}, serializedTagTypes={}",
                        record.id,
                        resolvedFrameIndex,
                        liveRemaining,
                        frameClasses,
                        serializedTagTypes
                    )
                    return ForEachConversionResult.NoConversion
                }
                null
            }

            else -> serializeIotasOrNull(explicitBaseStack, owner)
                ?: return ForEachConversionResult.Failed("base_stack_serialize_failed")
        }
        val accTags = serializeIotasOrNull(foreachFrame.acc, owner)
            ?: return ForEachConversionResult.Failed("accumulator_serialize_failed")

        var inputListId: UUID? = null
        var accumulatorListId: UUID? = null
        fun rollbackCreatedLists() {
            inputListId?.let(store::deleteLargeList)
            accumulatorListId?.let(store::deleteLargeList)
        }

        try {
            val listChunkSize = ManifestationConfig.splinterLargeListChunkSize().coerceAtLeast(1)
            inputListId = store.putLargeList(
                owner = record.owner,
                sourceSplinterId = record.id,
                itemTags = inputTags,
                createdGameTime = level.gameTime,
                chunkSize = listChunkSize
            )
            accumulatorListId = store.putLargeList(
                owner = record.owner,
                sourceSplinterId = record.id,
                itemTags = accTags,
                createdGameTime = level.gameTime,
                chunkSize = listChunkSize
            )

            val replacementFrame = ManifestationExternalizedForEachFrame(
                owner = record.owner,
                sourceSplinterId = record.id,
                inputListId = inputListId!!,
                cursor = 0,
                totalCount = liveRemaining,
                bodyCodeTags = bodyCodeTags,
                baseStackTags = baseStackTags,
                accumulatorListId = accumulatorListId!!
            )

            val replacedTags = replaceForeachTagWithExternalizedFrameTag(
                frameIndex = resolvedFrameIndex,
                serializedTags = serializedTags,
                replacementFrame = replacementFrame
            ) ?: run {
                rollbackCreatedLists()
                Manifestation.LOGGER.error(
                    "Spliter - Externalized foreach conversion failed: id={}, reason=serialized_tag_mismatch, frameIndex={}, serializedTagTypes={}",
                    record.id,
                    resolvedFrameIndex,
                    serializedTagTypes
                )
                return ForEachConversionResult.Failed("serialized_tag_mismatch")
            }

            Manifestation.LOGGER.warn(
                "Spliter - Externalized foreach conversion: id={}, frameIndex={}, remaining={}, inputListId={}, accumulatorListId={}, chunkSize={}, frameClasses={}, tagsBefore={}, tagsAfter={}",
                record.id,
                resolvedFrameIndex,
                liveRemaining,
                inputListId,
                accumulatorListId,
                listChunkSize,
                frameClasses,
                serializedTagTypes,
                SplinterLargeForEachRunner.continuationTagTypes(replacedTags)
            )

            return ForEachConversionResult.Converted(replacedTags)
        } catch (t: Throwable) {
            rollbackCreatedLists()
            Manifestation.LOGGER.error(
                "Spliter - Externalized foreach conversion failed: id={}, frameIndex={}, reason={}",
                record.id,
                resolvedFrameIndex,
                t.message ?: "",
                t
            )
            return ForEachConversionResult.Failed("conversion_exception")
        }
    }

    internal fun persistContinuationState(
        record: SplinterStateStore.SplinterRecord,
        imageTag: CompoundTag?,
        continuationTags: List<CompoundTag>
    ) {
        record.imageTag = imageTag?.copy()
        record.continuationTags.clear()
        for (tag in continuationTags) {
            record.continuationTags.add(tag.copy())
        }
    }

    internal fun replaceForeachTagWithExternalizedFrameTag(
        frameIndex: Int,
        serializedTags: List<CompoundTag>,
        replacementFrame: ManifestationExternalizedForEachFrame
    ): MutableList<CompoundTag>? {
        if (frameIndex !in serializedTags.indices) {
            return null
        }
        val targetTag = serializedTags[frameIndex]
        if (continuationTagType(targetTag) != "hexcasting:foreach") {
            return null
        }

        val out = serializedTags.map { it.copy() }.toMutableList()
        val replacementTag = CompoundTag().apply {
            putString("hexcasting:type", ManifestationExternalizedForEachFrame.TYPE_ID)
            put("hexcasting:data", replacementFrame.serializeToNBT())
        }
        out[frameIndex] = replacementTag
        return out
    }

    private fun serializeIotasOrNull(
        iotas: Iterable<Iota>,
        owner: ServerPlayer
    ): MutableList<CompoundTag>? {
        val out = mutableListOf<CompoundTag>()
        for (iota in iotas) {
            try {
                throwIfOtherTrueNameEmbedded(iota, owner)
            } catch (_: Throwable) {
                return null
            }
            val serialized = try {
                IotaType.serialize(iota)
            } catch (_: Throwable) {
                return null
            }
            if (serialized !is CompoundTag) {
                return null
            }
            out.add(serialized.copy())
        }
        return out
    }

    private fun serializedForeachDataSize(tag: CompoundTag?): Int {
        if (tag == null) {
            return -1
        }
        if (continuationTagType(tag) != "hexcasting:foreach") {
            return -1
        }
        if (!KotlinNbtCompat.contains(tag, "hexcasting:data", Tag.TAG_COMPOUND.toInt())) {
            return -1
        }
        val frameData = KotlinNbtCompat.getCompound(tag, "hexcasting:data")
        if (!KotlinNbtCompat.contains(frameData, "data", Tag.TAG_LIST.toInt())) {
            return -1
        }
        return KotlinNbtCompat.getList(frameData, "data", Tag.TAG_COMPOUND.toInt()).size
    }

    private fun maybeLogRenewRequested(
        source: SplinterStateStore.SplinterRecord,
        position: Vec3,
        delayTicks: Long
    ) {
        if (!ManifestationConfig.splinterDebugSliceTelemetry()) {
            return
        }

        Manifestation.LOGGER.info(
            "Splinter - Renew requested: source={}, owner={}, position={}, delayTicks={}, started={}, frames={}, stack={}",
            source.id,
            source.owner,
            position,
            delayTicks,
            source.started,
            source.lastObservedContinuationSize ?: 0,
            source.lastObservedStackSize ?: 0
        )
    }

    private fun maybeLogRenewCommitted(
        source: SplinterStateStore.SplinterRecord,
        renewed: SplinterStateStore.SplinterRecord
    ) {
        if (!ManifestationConfig.splinterDebugSliceTelemetry()) {
            return
        }

        Manifestation.LOGGER.info(
            "Splinter - Renew committed after completion: source={}, renewed={}, owner={}, position={}, castAt={}",
            source.id,
            renewed.id,
            renewed.owner,
            renewed.position,
            renewed.castAtGameTime
        )
    }

    private fun createRenewedRecordAfterCompletion(
        source: SplinterStateStore.SplinterRecord,
        level: ServerLevel,
        renewPosition: Vec3,
        delayTicks: Long
    ): SplinterStateStore.SplinterRecord {
        val castAt = level.gameTime + delayTicks.coerceAtLeast(0L)
        val copiedPayload = source.payloadTags.map { it.copy() }.toMutableList()

        return SplinterStateStore.SplinterRecord(
            id = UUID.randomUUID(),
            owner = source.owner,
            dimensionId = source.dimensionId,
            position = renewPosition,
            anchorPosition = source.anchorPosition,
            circleImpetusPos = source.circleImpetusPos,
            castAtGameTime = castAt,
            castingHand = source.castingHand,
            payloadTags = copiedPayload,
            ravenmindTag = source.ravenmindTag?.copy(),
            started = false,
            imageTag = null,
            continuationTags = mutableListOf(),
            createdGameTime = castAt,
            lastRunGameTime = castAt,
            lastProgressGameTime = castAt,
            totalHexOpsRun = 0L,
            totalFrameStepsRun = 0L,
            overBudgetCount = 0,
            ambitRadius = source.ambitRadius,
            allowRenew = source.allowRenew,
            lastObservedStackSize = null,
            lastObservedContinuationSize = null,
            pendingRenewPosition = null,
            pendingRenewDelayTicks = null,
            pendingRenewRequestedGameTime = null
        )
    }

    private fun deserializePayload(
        record: SplinterStateStore.SplinterRecord,
        level: ServerLevel,
        owner: ServerPlayer
    ): MutableList<Iota> {
        val payload = mutableListOf<Iota>()
        for (tag in record.payloadTags) {
            try {
                val iota = IotaType.deserialize(tag.copy(), level)
                if (iota != null) {
                    throwIfOtherTrueNameEmbedded(iota, owner)
                    payload.add(iota)
                }
            } catch (mishap: Mishap) {
                throw mishap
            } catch (_: Throwable) {
                // Skip corrupt iotas and continue.
            }
        }
        return payload
    }

    private fun buildInitialContinuation(payload: List<Iota>): SpellContinuation.NotDone? {
        val spellList = SpellList.LList(payload)
        val continuation = SpellContinuation.Done.pushFrame(FrameEvaluate(spellList, false))
        return continuation as? SpellContinuation.NotDone
    }

    private fun serializeContinuation(continuation: SpellContinuation): MutableList<CompoundTag> {
        val out = mutableListOf<CompoundTag>()
        for (frameTag in continuation.getNBTFrames()) {
            out.add(frameTag.copy())
        }
        return out
    }

    private fun deserializeContinuation(tags: List<CompoundTag>, level: ServerLevel): SpellContinuation.NotDone? {
        if (tags.isEmpty()) {
            return null
        }

        var continuation: SpellContinuation = SpellContinuation.Done
        for (idx in tags.indices.reversed()) {
            val frame = try {
                ContinuationFrame.fromNBT(tags[idx].copy(), level)
            } catch (e: Throwable) {
                Manifestation.LOGGER.warn(
                    "Manifestation: failed to deserialize splinter continuation frame at index {} of {}. type={}.",
                    idx,
                    tags.size,
                    continuationTagType(tags[idx]),
                    e
                )
                return null
            }
            continuation = continuation.pushFrame(frame)
        }
        if (ManifestationConfig.splinterDebugSliceTelemetry()) {
            val classes = continuationFrameClassNames(continuation)
            Manifestation.LOGGER.info(
                "Manifestation splinter continuation deserialize result: frames={}, classes={}",
                classes.size,
                classes
            )
        }
        return continuation as? SpellContinuation.NotDone
    }

    private fun maybeLogSliceTelemetry(
        record: SplinterStateStore.SplinterRecord,
        result: SliceResult?,
        elapsedNanos: Long,
        overBudgetBefore: Int,
        overBudgetAfter: Int,
        killReason: String?,
        didRunSlice: Boolean
    ) {
        if (!ManifestationConfig.splinterDebugSliceTelemetry()) {
            return
        }

        val elapsedMicros = elapsedNanos / 1_000L
        val elapsedMillis = elapsedNanos / 1_000_000.0
        Manifestation.LOGGER.info(
            "Manifestation splinter slice: id={}, owner={}, ranSlice={}, steps={}, elapsedMicros={}, elapsedMillis={}, " +
                "opsBefore={}, opsAfter={}, stackBefore={}, stackAfter={}, framesBefore={}, framesAfter={}, " +
                "completed={}, didProgress={}, overBudgetBefore={}, overBudgetAfter={}, overBudgetChanged={}, killReason={}",
            record.id,
            record.owner,
            didRunSlice,
            result?.stepsRun ?: 0,
            elapsedMicros,
            elapsedMillis,
            result?.beforeOps ?: 0L,
            result?.afterOps ?: 0L,
            result?.beforeStackSize ?: (record.lastObservedStackSize ?: 0),
            result?.afterStackSize ?: (record.lastObservedStackSize ?: 0),
            result?.beforeContinuationFrameCount ?: (record.lastObservedContinuationSize ?: 0),
            result?.afterContinuationFrameCount ?: (record.lastObservedContinuationSize ?: 0),
            result?.completed ?: false,
            result?.didProgress ?: false,
            overBudgetBefore,
            overBudgetAfter,
            overBudgetBefore != overBudgetAfter,
            killReason ?: ""
        )
    }

    private fun continuationTagType(tag: CompoundTag): String {
        return when {
            KotlinNbtCompat.contains(tag, "hexcasting:type", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "hexcasting:type")
            KotlinNbtCompat.contains(tag, "type", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "type")
            KotlinNbtCompat.contains(tag, "op", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "op")
            KotlinNbtCompat.contains(tag, "action", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "action")
            else -> ""
        }
    }

    private fun continuationFrameClassNames(continuation: SpellContinuation): List<String> {
        val names = mutableListOf<String>()
        var current = continuation
        while (current is SpellContinuation.NotDone) {
            names.add(current.frame::class.java.name)
            current = current.next
        }
        return names
    }

    private fun serializeImage(image: CastingImage): CompoundTag {
        return image.serializeToNbt().copy()
    }

    private fun deserializeImage(tag: CompoundTag?, level: ServerLevel): CastingImage? {
        if (tag == null) {
            return null
        }
        return try {
            CastingImage.loadFromNbt(tag.copy(), level)
        } catch (_: Throwable) {
            null
        }
    }

    private fun continuationFrameCount(continuation: SpellContinuation): Int {
        var count = 0
        var current = continuation
        while (current is SpellContinuation.NotDone) {
            count++
            current = current.next
        }
        return count
    }

    private fun compoundTagsEqual(a: CompoundTag?, b: CompoundTag?): Boolean {
        if (a == null && b == null) {
            return true
        }
        if (a == null || b == null) {
            return false
        }
        return a == b
    }

    private fun compoundTagListsEqual(a: List<CompoundTag>, b: List<CompoundTag>): Boolean {
        if (a.size != b.size) {
            return false
        }
        for (idx in a.indices) {
            if (a[idx] != b[idx]) {
                return false
            }
        }
        return true
    }

    private fun levelForRecord(server: MinecraftServer, record: SplinterStateStore.SplinterRecord): ServerLevel? {
        val levelKey = ResourceKey.create(
            net.minecraft.core.registries.Registries.DIMENSION,
            ResourceLocation(record.dimensionId)
        )
        return server.getLevel(levelKey)
    }

    private fun hasValidCircleImpetus(level: ServerLevel, record: SplinterStateStore.SplinterRecord): Boolean {
        val impetusVec = record.circleImpetusPos ?: return true
        val impetusPos = BlockPos.containing(impetusVec.x, impetusVec.y, impetusVec.z)
        val chunkX = impetusPos.x shr 4
        val chunkZ = impetusPos.z shr 4
        if (!level.chunkSource.hasChunk(chunkX, chunkZ)) {
            return true
        }
        return level.getBlockEntity(impetusPos) is BlockEntityAbstractImpetus
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
            BlockPos.containing(impetusVec.x, impetusVec.y, impetusVec.z),
            record.ambitRadius
        )
        val sourceName = if (record.allowRenew) {
            Component.translatable("block.manifestation.splinter_caster")
        } else {
            Component.literal("Saturn's Gambit")
        }
        val msg = mishap.errorMessageWithName(
            env,
            Mishap.Context(null, sourceName)
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
            val records = store.allByOwner(owner).filter { it.allowRenew }
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
        if (!KotlinNbtCompat.contains(image.userData, HexAPI.RAVENMIND_USERDATA)) {
            return null
        }
        return KotlinNbtCompat.getCompound(image.userData, HexAPI.RAVENMIND_USERDATA).copy()
    }

    private fun buildStartingImage(ravenmind: CompoundTag?): CastingImage {
        if (ravenmind == null) {
            return CastingImage()
        }

        val userData = CompoundTag()
        KotlinNbtCompat.put(userData, HexAPI.RAVENMIND_USERDATA, ravenmind.copy())
        return CastingImage().copy(userData = userData)
    }

    internal fun throwIfOtherTrueNameEmbedded(iota: Iota, caster: ServerPlayer) {
        val trueName = MishapOthersName.getTrueNameFromDatum(iota, caster)
        if (trueName != null) {
            throw MishapOthersName(trueName)
        }
    }

    private fun validateRavenmindForPersistence(ravenmind: CompoundTag?, caster: ServerPlayer) {
        if (ravenmind == null) {
            return
        }
        val decoded = IotaType.deserialize(ravenmind.copy(), caster.serverLevel()) ?: return
        throwIfOtherTrueNameEmbedded(decoded, caster)
    }

    private fun validatedRavenmindForExecution(
        ravenmind: CompoundTag?,
        level: ServerLevel,
        caster: ServerPlayer
    ): CompoundTag? {
        if (ravenmind == null) {
            return null
        }
        val decoded = IotaType.deserialize(ravenmind.copy(), level) ?: return null
        throwIfOtherTrueNameEmbedded(decoded, caster)
        return ravenmind
    }
}

