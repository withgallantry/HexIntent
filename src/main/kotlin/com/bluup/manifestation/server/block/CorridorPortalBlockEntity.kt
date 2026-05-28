package com.bluup.manifestation.server.block

// This got so much bigger than I'd planned, just because I wanted it to look nice and kept adding

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.pigment.FrozenPigment
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.server.PortalOwnershipStore
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CorridorPortalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.CORRIDOR_PORTAL_BLOCK_ENTITY, pos, state) {

    private data class PendingPlayerTeleport(
        val playerUuid: UUID,
        val targetDimensionId: String,
        val targetPortalPos: BlockPos,
        val exitPos: Vec3,
        val exitYaw: Float,
        val triggerSound: Boolean
    )

    private var targetDimensionId: String? = null
    private var targetPos: BlockPos? = null
    private var ownerUuid: UUID? = null
    private var sustainMediaRemaining: Long = 0L
    private var lastSustainDrainGameTime: Long = 0L
    private var openedAtGameTime: Long = 0L
    private var collapseStartedAtGameTime: Long = -1L
    private var renderScale: Float = 1.0f
    private var renderYawDegrees: Float = 0.0f
    private var thresholdMode: Boolean = false
    private var permanentFrameMode: Boolean = false
    private var localPermanentFrame: PermanentThresholdFrame? = null
    private var linkedPermanentFrame: PermanentThresholdFrame? = null
    private var portalBackdropColor: Int = DEFAULT_PORTAL_BACKDROP_COLOR
    private var portalMidColor: Int = DEFAULT_PORTAL_MID_COLOR
    private var portalHighlightColor: Int = DEFAULT_PORTAL_HIGHLIGHT_COLOR
    private var portalFrameColor: Int = DEFAULT_PORTAL_FRAME_COLOR
    private var portalResolvedTintColor: Int = DEFAULT_PORTAL_TINT_COLOR
    private var portalTintColorizer: FrozenPigment? = null
    private var portalLabel: String? = null
    private val thresholdPatterns: MutableList<CompoundTag> = mutableListOf()

    private val cooldownUntilByEntity: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val pendingPlayerTeleports: MutableMap<UUID, PendingPlayerTeleport> = ConcurrentHashMap()

    fun linkTo(
        level: ServerLevel,
        target: BlockPos,
        targetDimension: String,
        owner: UUID?,
        mediaBudget: Long,
        scale: Float,
        yawDegrees: Float,
        permanentFrame: Boolean = false,
        localFrame: PermanentThresholdFrame? = null,
        linkedFrame: PermanentThresholdFrame? = null
    ) {
        targetDimensionId = targetDimension
        targetPos = target.immutable()
        ownerUuid = owner
        sustainMediaRemaining = mediaBudget.coerceAtLeast(0L)
        lastSustainDrainGameTime = level.gameTime
        openedAtGameTime = level.gameTime
        collapseStartedAtGameTime = -1L
        renderScale = if (permanentFrame) 1.0f else scale.coerceIn(0.1f, 3.0f)
        renderYawDegrees = Mth.wrapDegrees(yawDegrees)
        thresholdMode = false
        permanentFrameMode = permanentFrame
        localPermanentFrame = if (permanentFrame) localFrame else null
        linkedPermanentFrame = if (permanentFrame) linkedFrame else null
        updateThresholdBlockState(level, false)
        resetPortalColors()
        portalLabel = null
        thresholdPatterns.clear()

        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    fun configureThresholdTrigger(
        level: ServerLevel,
        patterns: List<Iota>,
        owner: UUID?,
        mediaBudget: Long,
        scale: Float,
        yawDegrees: Float
    ) {
        targetDimensionId = null
        targetPos = null
        ownerUuid = owner
        sustainMediaRemaining = mediaBudget.coerceAtLeast(0L)
        lastSustainDrainGameTime = level.gameTime
        openedAtGameTime = level.gameTime
        collapseStartedAtGameTime = -1L
        renderScale = scale.coerceIn(0.1f, 3.0f)
        renderYawDegrees = Mth.wrapDegrees(yawDegrees)
        thresholdMode = true
        permanentFrameMode = false
        localPermanentFrame = null
        linkedPermanentFrame = null
        updateThresholdBlockState(level, true)
        resetPortalColors()
        portalLabel = null
        thresholdPatterns.clear()

        for (pattern in patterns) {
            val serialized = IotaType.serialize(pattern)
            if (serialized is CompoundTag) {
                thresholdPatterns.add(serialized.copy())
            }
        }

        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    fun beginCollapse(level: ServerLevel, startTick: Long = level.gameTime) {
        if (collapseStartedAtGameTime >= 0L) {
            return
        }

        collapseStartedAtGameTime = startTick
        setChanged()
        level.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    fun renderEnvelope(partialTick: Float): Float {
        val world = level ?: return 1.0f
        val now = world.gameTime.toDouble() + partialTick.toDouble()
        val openAnimTicks = if (permanentFrameMode) PERMANENT_OPEN_ANIM_TICKS else OPEN_ANIM_TICKS

        val open = if (openedAtGameTime <= 0L) {
            1.0f
        } else {
            smoothstep(progress(now, openedAtGameTime, openAnimTicks))
        }

        val close = if (collapseStartedAtGameTime < 0L) {
            1.0f
        } else {
            1.0f - smoothstep(progress(now, collapseStartedAtGameTime, CLOSE_ANIM_TICKS))
        }

        return (open * close).coerceIn(0.0f, 1.0f)
    }

    fun collapseProgress(partialTick: Float): Float {
        val world = level ?: return 0.0f
        val start = collapseStartedAtGameTime
        if (start < 0L) {
            return 0.0f
        }

        val now = world.gameTime.toDouble() + partialTick.toDouble()
        return progress(now, start, CLOSE_ANIM_TICKS)
    }

    fun getOpenedAtGameTime(): Long = openedAtGameTime

    fun getRenderTargetPos(): BlockPos? = targetPos

    fun getRenderTargetDimensionId(): String? = targetDimensionId

    fun getRenderScale(): Float = if (permanentFrameMode) 1.0f else renderScale

    fun getRenderYawDegrees(): Float = renderYawDegrees

    fun isThresholdMode(): Boolean = thresholdMode

    fun isPermanentFrameMode(): Boolean = permanentFrameMode

    fun getPortalBackdropColor(): Int = portalBackdropColor

    fun getPortalMidColor(): Int = portalMidColor

    fun getPortalHighlightColor(): Int = portalHighlightColor

    fun getPortalFrameColor(): Int = portalFrameColor

    fun getPortalResolvedTintColor(): Int = portalResolvedTintColor

    fun samplePortalTintColor(time: Float, samplePosition: Vec3): Int {
        val pigment = portalTintColorizer ?: return portalResolvedTintColor
        return try {
            pigment.getColorProvider().getColor(time, samplePosition) and 0xFFFFFF
        } catch (_: Throwable) {
            portalResolvedTintColor
        }
    }

    fun getPortalLabel(): String? = portalLabel

    fun setPortalLabel(label: String?) {
        val normalized = label?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        if (portalLabel == normalized) {
            return
        }

        portalLabel = normalized
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    fun setPortalColors(backdropColor: Int, midColor: Int, highlightColor: Int, frameColor: Int) {
        portalBackdropColor = backdropColor and 0xFFFFFF
        portalMidColor = midColor and 0xFFFFFF
        portalHighlightColor = highlightColor and 0xFFFFFF
        portalFrameColor = frameColor and 0xFFFFFF

        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    fun applyPortalAccentTint(
        @Suppress("UNUSED_PARAMETER") dyeColor: DyeColor?,
        resolvedTintRgb: Int,
        colorizer: FrozenPigment?
    ) {
        portalResolvedTintColor = resolvedTintRgb and 0xFFFFFF
        portalTintColorizer = colorizer

        // Keep base portal and rim colors stable; renderer consumes the tint only for internal accents.
        setPortalColors(
            DEFAULT_PORTAL_BACKDROP_COLOR,
            DEFAULT_PORTAL_MID_COLOR,
            DEFAULT_PORTAL_HIGHLIGHT_COLOR,
            DEFAULT_PORTAL_FRAME_COLOR
        )
    }

    fun serverTick(level: ServerLevel) {
        processPendingPlayerTeleports(level)

        if (thresholdMode) {
            serverTickThreshold(level)
            return
        }

        if (permanentFrameMode) {
            serverTickPermanent(level)
            return
        }

        if (!isSustainDriver(level)) {
            return
        }

        if (collapseStartedAtGameTime >= 0L) {
            if (level.gameTime >= collapseStartedAtGameTime + CLOSE_ANIM_TICKS) {
                removePairNow(level)
            }
            return
        }

        if (sustainMediaRemaining <= 0L) {
            startPairCollapse(level)
            return
        }

        if (level.gameTime % FLOW_PARTICLE_INTERVAL_TICKS == 0L) {
            spawnLinkedFlowParticles(level)
        }

        if (lastSustainDrainGameTime <= 0L) {
            lastSustainDrainGameTime = level.gameTime
            return
        }

        val elapsed = level.gameTime - lastSustainDrainGameTime
        if (elapsed < TICKS_PER_DRAIN_STEP) {
            return
        }

        val steps = (elapsed / TICKS_PER_DRAIN_STEP).coerceAtLeast(1L)
        sustainMediaRemaining -= (steps * THRESHOLD_MEDIA_DRAIN_PER_STEP)
        lastSustainDrainGameTime += steps * TICKS_PER_DRAIN_STEP
        setChanged()

        if (sustainMediaRemaining <= 0L) {
            startPairCollapse(level)
        }
    }

    private fun serverTickThreshold(level: ServerLevel) {
        if (collapseStartedAtGameTime >= 0L) {
            if (level.gameTime >= collapseStartedAtGameTime + CLOSE_ANIM_TICKS) {
                removeSingleThresholdNow(level)
            }
            return
        }

        if (sustainMediaRemaining <= 0L) {
            beginCollapse(level)
            return
        }

        if (lastSustainDrainGameTime <= 0L) {
            lastSustainDrainGameTime = level.gameTime
            return
        }

        val elapsed = level.gameTime - lastSustainDrainGameTime
        if (elapsed < TICKS_PER_DRAIN_STEP) {
            return
        }

        val steps = (elapsed / TICKS_PER_DRAIN_STEP).coerceAtLeast(1L)
        sustainMediaRemaining -= (steps * MEDIA_DRAIN_PER_STEP)
        lastSustainDrainGameTime += steps * TICKS_PER_DRAIN_STEP
        setChanged()

        if (sustainMediaRemaining <= 0L) {
            beginCollapse(level)
        }
    }

    private fun serverTickPermanent(level: ServerLevel) {
        if (collapseStartedAtGameTime >= 0L) {
            if (level.gameTime >= collapseStartedAtGameTime + CLOSE_ANIM_TICKS) {
                removePairNow(level)
            }
            return
        }

        if (!ensurePermanentLinkValid(level)) {
            startPairCollapse(level)
        }
    }

    fun tryTeleport(level: ServerLevel, entity: Entity) {
        if (thresholdMode) {
            return
        }

        if (permanentFrameMode && !ensurePermanentLinkValid(level)) {
            return
        }

        if (entity.isPassenger || entity.isVehicle) {
            return
        }

        val targetDim = targetDimensionId ?: return
        val target = targetPos ?: return
        val now = level.gameTime
        val uuid = entity.uuid

        val cooldownUntil = cooldownUntilByEntity[uuid] ?: 0L
        if (now < cooldownUntil) {
            return
        }

        if (collapseStartedAtGameTime >= 0L) {
            return
        }

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
        val targetLevel = level.server.getLevel(targetKey) ?: return

        val targetState = targetLevel.getBlockState(target)
        if (targetState.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return
        }

        val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity ?: return
        targetLevel.getChunk(target)

        val sourceNormal = normalFromYaw(renderYawDegrees)
        val sourceCenter = Vec3.atCenterOf(worldPosition)
        val toEntity = entity.position().subtract(sourceCenter)
        val side = if (toEntity.dot(sourceNormal) >= 0.0) 1.0 else -1.0

        val targetNormal = normalFromYaw(targetPortal.renderYawDegrees)
        val targetCenter = Vec3.atCenterOf(target)
        val relativeY = (entity.y - worldPosition.y).coerceIn(0.05, 1.75)
        val scaledExitOffset = EXIT_OFFSET * targetPortal.getRenderScale().coerceIn(0.1f, 3.0f)
        val exitFacing = targetNormal.scale(side)
        val exitYaw = ((Mth.atan2(exitFacing.z, exitFacing.x) * (180.0 / Math.PI)) - 90.0).toFloat()
        val exitPos = targetCenter
            .add(targetNormal.scale(side * scaledExitOffset.toDouble()))
            .add(0.0, relativeY - 0.5, 0.0)

        val newCooldown = now + TELEPORT_COOLDOWN_TICKS
        cooldownUntilByEntity[uuid] = newCooldown
        targetPortal.cooldownUntilByEntity[uuid] = newCooldown

        val teleported = if (entity is ServerPlayer) {
            pendingPlayerTeleports[uuid] = PendingPlayerTeleport(
                playerUuid = uuid,
                targetDimensionId = targetLevel.dimension().location().toString(),
                targetPortalPos = target.immutable(),
                exitPos = exitPos,
                exitYaw = exitYaw,
                triggerSound = true
            )
            true
        } else {
            if (targetLevel == level) {
                entity.teleportTo(exitPos.x, exitPos.y, exitPos.z)
                entity.yRot = exitYaw
                entity.setYHeadRot(exitYaw)
                entity.setYBodyRot(exitYaw)
                true
            } else {
                false
            }
        }

        if (teleported) {
            playTeleportSound(level, worldPosition, targetLevel, target)
        } else if (entity is ServerPlayer) {
            Manifestation.LOGGER.info(
                "Manifestation: portal teleport did not settle player {} to target {} in dimension {} (actual dim={}, pos={})",
                entity.gameProfile.name,
                exitPos,
                targetLevel.dimension().location(),
                entity.serverLevel().dimension().location(),
                entity.position()
            )
        }
    }

    private fun processPendingPlayerTeleports(level: ServerLevel) {
        if (pendingPlayerTeleports.isEmpty()) {
            return
        }

        val iterator = pendingPlayerTeleports.values.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            val player = level.server.playerList.getPlayer(pending.playerUuid)
            if (player == null) {
                iterator.remove()
                continue
            }

            val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(pending.targetDimensionId))
            val targetLevel = level.server.getLevel(targetKey)
            if (targetLevel == null) {
                iterator.remove()
                continue
            }

            if (targetLevel.getBlockState(pending.targetPortalPos).block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                iterator.remove()
                continue
            }

            if (targetLevel == player.serverLevel()) {
                player.connection.teleport(pending.exitPos.x, pending.exitPos.y, pending.exitPos.z, pending.exitYaw, player.xRot)
            } else {
                player.teleportTo(targetLevel, pending.exitPos.x, pending.exitPos.y, pending.exitPos.z, pending.exitYaw, player.xRot)
            }

            val moved = player.serverLevel() == targetLevel && player.position().distanceToSqr(pending.exitPos) <= 9.0
            if (moved) {
                player.fallDistance = 0.0f
                player.deltaMovement = Vec3.ZERO
                player.setYHeadRot(pending.exitYaw)
                player.setYBodyRot(pending.exitYaw)
                player.yRot = pending.exitYaw
                if (pending.triggerSound) {
                    playTeleportSound(level, worldPosition, targetLevel, pending.targetPortalPos)
                }
            } else {
                Manifestation.LOGGER.info(
                    "Manifestation: deferred portal teleport did not settle player {} to target {} in dimension {} (actual dim={}, pos={})",
                    player.gameProfile.name,
                    pending.exitPos,
                    targetLevel.dimension().location(),
                    player.serverLevel().dimension().location(),
                    player.position()
                )
            }

            iterator.remove()
        }
    }

    fun tryTriggerThreshold(level: ServerLevel, entity: Entity) {
        if (!thresholdMode) {
            return
        }

        if (entity.isPassenger || entity.isVehicle) {
            return
        }

        if (collapseStartedAtGameTime >= 0L) {
            return
        }

        val now = level.gameTime
        val uuid = entity.uuid
        val cooldownUntil = cooldownUntilByEntity[uuid] ?: 0L
        if (now < cooldownUntil) {
            return
        }

        cooldownUntilByEntity[uuid] = now + THRESHOLD_TRIGGER_COOLDOWN_TICKS
        if (runThresholdPatterns(level, entity)) {
            // Threshold mode is single-fire: once crossed, it begins dissolving.
            beginCollapse(level)
        }
    }

    private fun runThresholdPatterns(level: ServerLevel, entity: Entity): Boolean {
        if (thresholdPatterns.isEmpty()) {
            return false
        }

        val iotas = mutableListOf<Iota>()
        for (tag in thresholdPatterns) {
            try {
                val iota = IotaType.deserialize(tag.copy(), level)
                if (iota != null) {
                    iotas.add(iota)
                }
            } catch (_: Throwable) {
                // Skip corrupted entries and continue with the remaining trigger list.
            }
        }

        if (iotas.isEmpty()) {
            return false
        }

        val env = ThresholdTriggerCastEnv(level, worldPosition, this, entity as? LivingEntity)
        // Threshold payloads always execute with the triggering entity pre-seeded on stack according to what I read.
        return tryExecuteThresholdPatterns(level, iotas, env, listOf(EntityIota(entity)))
    }

    private fun tryExecuteThresholdPatterns(
        level: ServerLevel,
        iotas: List<Iota>,
        env: ThresholdTriggerCastEnv,
        initialStack: List<Iota>
    ): Boolean {
        try {
            val vm = CastingVM.empty(env)
            vm.image = vm.image.copy(stack = initialStack)
            val clientInfo = vm.queueExecuteAndWrapIotas(iotas, level)
            val success = clientInfo.resolutionType.success
            if (!success) {
                Manifestation.LOGGER.info(
                    "Manifestation: threshold trigger produced non-success resolution {} at {} (stack seed = {})",
                    clientInfo.resolutionType,
                    worldPosition,
                    if (initialStack.isEmpty()) "empty" else "entity"
                )
            }
            return success
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn("Manifestation: threshold trigger execution failed at {} (stack seed = {})", worldPosition, if (initialStack.isEmpty()) "empty" else "entity", t)
            return false
        }
    }

    fun extractThresholdMedia(cost: Long, simulate: Boolean): Long {
        if (cost <= 0L) {
            return 0L
        }

        val available = sustainMediaRemaining.coerceAtLeast(0L)
        val spent = minOf(cost, available)
        val remaining = cost - spent
        if (remaining > 0L && simulate) {
            Manifestation.LOGGER.info(
                "Manifestation: threshold media shortfall at {} (required={}, available={}, missing={})",
                worldPosition,
                cost,
                available,
                remaining
            )
        }
        if (!simulate && spent > 0L) {
            sustainMediaRemaining = available - spent
            setChanged()
        }

        return remaining
    }

    private fun playTeleportSound(sourceLevel: ServerLevel, sourcePos: BlockPos, targetLevel: ServerLevel, targetPos: BlockPos) {
        val pitch = 0.93f + (sourceLevel.random.nextFloat() * 0.1f)
        sourceLevel.playSound(null, sourcePos, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.BLOCKS, 0.45f, pitch)
        targetLevel.playSound(null, targetPos, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.BLOCKS, 0.5f, pitch + 0.06f)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)

        targetDimensionId = if (tag.contains(TAG_TARGET_DIMENSION)) tag.getString(TAG_TARGET_DIMENSION) else null
        targetPos = if (tag.contains(TAG_TARGET_POS)) BlockPos.of(tag.getLong(TAG_TARGET_POS)) else null
        ownerUuid = if (tag.hasUUID(TAG_OWNER_UUID)) tag.getUUID(TAG_OWNER_UUID) else null
        sustainMediaRemaining = tag.getLong(TAG_SUSTAIN_MEDIA_REMAINING)
        lastSustainDrainGameTime = tag.getLong(TAG_LAST_SUSTAIN_DRAIN_TIME)
        openedAtGameTime = tag.getLong(TAG_OPENED_AT_TIME)
        collapseStartedAtGameTime = if (tag.contains(TAG_COLLAPSE_STARTED_AT_TIME)) {
            tag.getLong(TAG_COLLAPSE_STARTED_AT_TIME)
        } else {
            -1L
        }
        renderScale = if (tag.contains(TAG_RENDER_SCALE)) tag.getFloat(TAG_RENDER_SCALE) else 1.0f
        renderYawDegrees = if (tag.contains(TAG_RENDER_YAW_DEGREES)) tag.getFloat(TAG_RENDER_YAW_DEGREES) else 0.0f
        thresholdMode = tag.getBoolean(TAG_THRESHOLD_MODE)
        permanentFrameMode = tag.getBoolean(TAG_PERMANENT_FRAME_MODE)
        localPermanentFrame = if (tag.contains(TAG_LOCAL_PERMANENT_FRAME, Tag.TAG_COMPOUND.toInt())) {
            PermanentThresholdFrame.deserialize(tag.getCompound(TAG_LOCAL_PERMANENT_FRAME))
        } else {
            null
        }
        linkedPermanentFrame = if (tag.contains(TAG_LINKED_PERMANENT_FRAME, Tag.TAG_COMPOUND.toInt())) {
            PermanentThresholdFrame.deserialize(tag.getCompound(TAG_LINKED_PERMANENT_FRAME))
        } else {
            null
        }
        portalBackdropColor = if (tag.contains(TAG_PORTAL_BACKDROP_COLOR)) tag.getInt(TAG_PORTAL_BACKDROP_COLOR) else DEFAULT_PORTAL_BACKDROP_COLOR
        portalMidColor = if (tag.contains(TAG_PORTAL_MID_COLOR)) tag.getInt(TAG_PORTAL_MID_COLOR) else DEFAULT_PORTAL_MID_COLOR
        portalHighlightColor = if (tag.contains(TAG_PORTAL_HIGHLIGHT_COLOR)) tag.getInt(TAG_PORTAL_HIGHLIGHT_COLOR) else DEFAULT_PORTAL_HIGHLIGHT_COLOR
        portalFrameColor = if (tag.contains(TAG_PORTAL_FRAME_COLOR)) tag.getInt(TAG_PORTAL_FRAME_COLOR) else DEFAULT_PORTAL_FRAME_COLOR
        portalResolvedTintColor = if (tag.contains(TAG_PORTAL_TINT_COLOR)) tag.getInt(TAG_PORTAL_TINT_COLOR) else DEFAULT_PORTAL_TINT_COLOR
        portalTintColorizer = if (tag.contains(TAG_PORTAL_TINT_COLORIZER, Tag.TAG_COMPOUND.toInt())) {
            FrozenPigment.fromNBT(tag.getCompound(TAG_PORTAL_TINT_COLORIZER))
        } else {
            null
        }
        portalLabel = if (tag.contains(TAG_PORTAL_LABEL)) tag.getString(TAG_PORTAL_LABEL) else null
        thresholdPatterns.clear()
        if (tag.contains(TAG_THRESHOLD_PATTERNS, Tag.TAG_LIST.toInt())) {
            val list = tag.getList(TAG_THRESHOLD_PATTERNS, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                thresholdPatterns.add(list.getCompound(i).copy())
            }
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)

        val dim = targetDimensionId
        if (dim != null) {
            tag.putString(TAG_TARGET_DIMENSION, dim)
        }
        val target = targetPos
        if (target != null) {
            tag.putLong(TAG_TARGET_POS, target.asLong())
        }
        val owner = ownerUuid
        if (owner != null) {
            tag.putUUID(TAG_OWNER_UUID, owner)
        }
        tag.putLong(TAG_SUSTAIN_MEDIA_REMAINING, sustainMediaRemaining)
        tag.putLong(TAG_LAST_SUSTAIN_DRAIN_TIME, lastSustainDrainGameTime)
        tag.putLong(TAG_OPENED_AT_TIME, openedAtGameTime)
        if (collapseStartedAtGameTime >= 0L) {
            tag.putLong(TAG_COLLAPSE_STARTED_AT_TIME, collapseStartedAtGameTime)
        }
        tag.putFloat(TAG_RENDER_SCALE, renderScale)
        tag.putFloat(TAG_RENDER_YAW_DEGREES, renderYawDegrees)
        tag.putBoolean(TAG_THRESHOLD_MODE, thresholdMode)
        tag.putBoolean(TAG_PERMANENT_FRAME_MODE, permanentFrameMode)
        localPermanentFrame?.let { tag.put(TAG_LOCAL_PERMANENT_FRAME, it.serialize()) }
        linkedPermanentFrame?.let { tag.put(TAG_LINKED_PERMANENT_FRAME, it.serialize()) }
        tag.putInt(TAG_PORTAL_BACKDROP_COLOR, portalBackdropColor)
        tag.putInt(TAG_PORTAL_MID_COLOR, portalMidColor)
        tag.putInt(TAG_PORTAL_HIGHLIGHT_COLOR, portalHighlightColor)
        tag.putInt(TAG_PORTAL_FRAME_COLOR, portalFrameColor)
        tag.putInt(TAG_PORTAL_TINT_COLOR, portalResolvedTintColor)
        val colorizer = portalTintColorizer
        if (colorizer != null) {
            tag.put(TAG_PORTAL_TINT_COLORIZER, colorizer.serializeToNBT())
        }
        val label = portalLabel
        if (!label.isNullOrEmpty()) {
            tag.putString(TAG_PORTAL_LABEL, label)
        }
        if (thresholdPatterns.isNotEmpty()) {
            val serialized = ListTag()
            for (pattern in thresholdPatterns) {
                serialized.add(pattern.copy())
            }
            tag.put(TAG_THRESHOLD_PATTERNS, serialized)
        }
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val TAG_TARGET_DIMENSION = "TargetDimension"
        private const val TAG_TARGET_POS = "TargetPos"
        private const val TAG_OWNER_UUID = "OwnerUuid"
        private const val TAG_SUSTAIN_MEDIA_REMAINING = "SustainMediaRemaining"
        private const val TAG_LAST_SUSTAIN_DRAIN_TIME = "LastSustainDrainGameTime"
        private const val TAG_OPENED_AT_TIME = "OpenedAtGameTime"
        private const val TAG_COLLAPSE_STARTED_AT_TIME = "CollapseStartedAtGameTime"
        private const val TAG_RENDER_SCALE = "RenderScale"
        private const val TAG_RENDER_YAW_DEGREES = "RenderYawDegrees"
        private const val TAG_THRESHOLD_MODE = "ThresholdMode"
        private const val TAG_PERMANENT_FRAME_MODE = "PermanentFrameMode"
        private const val TAG_LOCAL_PERMANENT_FRAME = "LocalPermanentFrame"
        private const val TAG_LINKED_PERMANENT_FRAME = "LinkedPermanentFrame"
        private const val TAG_THRESHOLD_PATTERNS = "ThresholdPatterns"
        private const val TAG_PORTAL_BACKDROP_COLOR = "PortalBackdropColor"
        private const val TAG_PORTAL_MID_COLOR = "PortalMidColor"
        private const val TAG_PORTAL_HIGHLIGHT_COLOR = "PortalHighlightColor"
        private const val TAG_PORTAL_FRAME_COLOR = "PortalFrameColor"
        private const val TAG_PORTAL_TINT_COLOR = "PortalTintColor"
        private const val TAG_PORTAL_TINT_COLORIZER = "PortalTintColorizer"
        private const val TAG_PORTAL_LABEL = "PortalLabel"

        private const val DEFAULT_PORTAL_BACKDROP_COLOR = 0x050A10
        private const val DEFAULT_PORTAL_MID_COLOR = 0x1E8F88
        private const val DEFAULT_PORTAL_HIGHLIGHT_COLOR = 0x8BFFF2
        private const val DEFAULT_PORTAL_FRAME_COLOR = 0x46D4C1
        private const val DEFAULT_PORTAL_TINT_COLOR = 0xB02CFF

        private const val TELEPORT_COOLDOWN_TICKS = 20L
        private const val THRESHOLD_TRIGGER_COOLDOWN_TICKS = 10L
        private const val EXIT_OFFSET = 0.80
        private const val TICKS_PER_DRAIN_STEP = 20L
        // Budgets are stored in raw media, but configured as dust-equivalent input.
        // 2 dust/sec = 20,000 media/sec at one drain step per second.
        private const val MEDIA_DRAIN_PER_STEP = 20_000L
        private const val THRESHOLD_MEDIA_DRAIN_PER_STEP = 20_000L
        private const val OPEN_ANIM_TICKS = 18L
        private const val PERMANENT_OPEN_ANIM_TICKS = 30L
        private const val CLOSE_ANIM_TICKS = 18L
        private const val FLOW_PARTICLE_INTERVAL_TICKS = 5L
        private const val FLOW_PARTICLES_PER_BURST = 1

        private fun normalFromYaw(yawDegrees: Float): Vec3 {
            val radians = Math.toRadians(yawDegrees.toDouble())
            return Vec3(-kotlin.math.sin(radians), 0.0, kotlin.math.cos(radians))
        }

        private fun progress(nowTicks: Double, startTick: Long, durationTicks: Long): Float {
            if (durationTicks <= 0L) {
                return 1.0f
            }
            val raw = (nowTicks - startTick.toDouble()) / durationTicks.toDouble()
            return raw.toFloat().coerceIn(0.0f, 1.0f)
        }

        private fun smoothstep(t: Float): Float = t * t * (3.0f - 2.0f * t)
    }

    private fun resetPortalColors() {
        portalBackdropColor = DEFAULT_PORTAL_BACKDROP_COLOR
        portalMidColor = DEFAULT_PORTAL_MID_COLOR
        portalHighlightColor = DEFAULT_PORTAL_HIGHLIGHT_COLOR
        portalFrameColor = DEFAULT_PORTAL_FRAME_COLOR
        portalResolvedTintColor = DEFAULT_PORTAL_TINT_COLOR
        portalTintColorizer = null
    }

    private fun mixRgb(a: Int, b: Int, t: Float): Int {
        val clamped = t.coerceIn(0.0f, 1.0f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF

        val r = Mth.lerp(clamped, ar.toFloat(), br.toFloat()).toInt().coerceIn(0, 255)
        val g = Mth.lerp(clamped, ag.toFloat(), bg.toFloat()).toInt().coerceIn(0, 255)
        val bCh = Mth.lerp(clamped, ab.toFloat(), bb.toFloat()).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bCh
    }

    private fun capLuma(rgb: Int, maxLuma: Float): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        val luma = ((0.2126f * r) + (0.7152f * g) + (0.0722f * b)) / 255.0f
        if (luma <= maxLuma || luma <= 0.0001f) {
            return rgb and 0xFFFFFF
        }

        val scale = (maxLuma / luma).coerceIn(0.0f, 1.0f)
        val nr = (r * scale).toInt().coerceIn(0, 255)
        val ng = (g * scale).toInt().coerceIn(0, 255)
        val nb = (b * scale).toInt().coerceIn(0, 255)
        return (nr shl 16) or (ng shl 8) or nb
    }

    private fun isSustainDriver(level: ServerLevel): Boolean {
        if (thresholdMode) {
            return true
        }

        val targetDim = targetDimensionId ?: return false
        val target = targetPos ?: return false

        // If counterpart is currently unloaded, let the loaded side drive sustain/collapse
        // so portals cannot pause forever just because the "driver" is unloaded.
        val targetKeyResource = ResourceLocation.tryParse(targetDim)
        if (targetKeyResource != null) {
            val targetKey = ResourceKey.create(Registries.DIMENSION, targetKeyResource)
            val targetLevel = level.server.getLevel(targetKey)
            if (targetLevel == null || !targetLevel.chunkSource.hasChunk(target.x shr 4, target.z shr 4)) {
                return true
            }
        }

        val selfKey = level.dimension().location().toString() + ":" + worldPosition.asLong()
        val targetKey = targetDim + ":" + target.asLong()
        return selfKey <= targetKey
    }

    private fun ensurePermanentLinkValid(level: ServerLevel): Boolean {
        if (!permanentFrameMode) {
            return true
        }

        val localFrame = resolveLocalPermanentFrame(level) ?: return false
        if (!PermanentThresholdFrames.isValid(level, localFrame)) {
            return false
        }

        val target = targetPos ?: return false
        val targetDim = targetDimensionId ?: return false
        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
        val targetLevel = level.server.getLevel(targetKey) ?: return false
        if (!targetLevel.chunkSource.hasChunk(target.x shr 4, target.z shr 4)) {
            return true
        }

        val targetState = targetLevel.getBlockState(target)
        if (targetState.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return false
        }

        val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity ?: return false
        if (!targetPortal.permanentFrameMode) {
            return false
        }

        if (!targetPortal.isReciprocalLinkTo(level.dimension().location().toString(), worldPosition)) {
            return false
        }

        val remoteFrame = targetPortal.resolveLocalPermanentFrame(targetLevel) ?: return false
        return PermanentThresholdFrames.isValid(targetLevel, remoteFrame)
    }

    private fun resolveLocalPermanentFrame(level: ServerLevel): PermanentThresholdFrame? {
        val existing = localPermanentFrame
        if (existing != null) {
            return existing
        }

        val resolved = PermanentThresholdFrames.findByAnchor(level, worldPosition, blockState.getValue(CorridorPortalBlock.AXIS))
        if (resolved != null) {
            localPermanentFrame = resolved
            setChanged()
        }
        return resolved
    }

    private fun startPairCollapse(level: ServerLevel) {
        if (thresholdMode) {
            beginCollapse(level)
            return
        }

        val startTick = level.gameTime
        beginCollapse(level, startTick)

        val target = targetPos
        val targetDim = targetDimensionId
        if (target != null && targetDim != null) {
            val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
            val targetLevel = level.server.getLevel(targetKey)
            val targetPortal = targetLevel?.getBlockEntity(target) as? CorridorPortalBlockEntity
            if (targetLevel != null && targetPortal != null) {
                targetPortal.beginCollapse(targetLevel, startTick)
            }
        }
    }

    private fun removePairNow(level: ServerLevel) {
        if (thresholdMode) {
            removeSingleThresholdNow(level)
            return
        }

        val target = targetPos
        val targetDim = targetDimensionId

        clearOwnershipReference(level)

        if (level.getBlockState(worldPosition).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(level, worldPosition)
            level.removeBlock(worldPosition, false)
        }

        if (target != null && targetDim != null) {
            val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
            val targetLevel = level.server.getLevel(targetKey)
            if (targetLevel != null) {
                val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity
                val stillReciprocal = targetPortal?.isReciprocalLinkTo(
                    level.dimension().location().toString(),
                    worldPosition
                ) == true

                if (stillReciprocal && targetLevel.getBlockState(target).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                    playCollapseEffects(targetLevel, target)
                    targetLevel.removeBlock(target, false)
                }
            }
        }
    }

    fun breakLinkedCounterpartNow(level: ServerLevel) {
        if (thresholdMode) {
            return
        }

        clearOwnershipReference(level)

        val target = targetPos ?: return
        val targetDim = targetDimensionId ?: return

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
        val targetLevel = level.server.getLevel(targetKey) ?: return
        val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity ?: return
        val stillReciprocal = targetPortal.isReciprocalLinkTo(
            level.dimension().location().toString(),
            worldPosition
        )
        if (stillReciprocal && targetLevel.getBlockState(target).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(targetLevel, target)
            targetLevel.removeBlock(target, false)
        }
    }

    private fun isReciprocalLinkTo(dimensionId: String, pos: BlockPos): Boolean {
        if (thresholdMode) {
            return false
        }
        return targetDimensionId == dimensionId && targetPos == pos
    }

    private fun playCollapseEffects(level: ServerLevel, pos: BlockPos) {
        val center = Vec3.atCenterOf(pos)
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 28, 0.32, 0.36, 0.32, 0.04)
        level.sendParticles(ParticleTypes.DRAGON_BREATH, center.x, center.y, center.z, 18, 0.24, 0.26, 0.24, 0.01)
        level.playSound(null, pos, SoundEvents.ENDER_EYE_DEATH, SoundSource.BLOCKS, 0.9f, 0.72f)
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.45f, 0.75f)
    }

    private fun spawnLinkedFlowParticles(level: ServerLevel) {
        if (thresholdMode) {
            return
        }

        if (collapseStartedAtGameTime >= 0L) {
            return
        }

        val ownScale = renderScale.coerceIn(0.1f, 3.0f)
        val ownTint = samplePortalTintColor(level.gameTime.toFloat() / 2.0f, Vec3.atCenterOf(worldPosition))
        spawnInflowParticles(level, worldPosition, renderYawDegrees, ownScale, ownTint)

        val target = targetPos ?: return
        val targetDim = targetDimensionId ?: return
        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
        val targetLevel = level.server.getLevel(targetKey) ?: return
        if (targetLevel.getBlockState(target).block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return
        }

        val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity
        val targetYaw = targetPortal?.renderYawDegrees ?: renderYawDegrees
        val targetScale = targetPortal?.renderScale?.coerceIn(0.1f, 3.0f) ?: ownScale
        val targetTint = targetPortal
            ?.samplePortalTintColor(targetLevel.gameTime.toFloat() / 2.0f, Vec3.atCenterOf(target))
            ?: ownTint
        spawnInflowParticles(targetLevel, target, targetYaw, targetScale, targetTint)
    }

    private fun spawnInflowParticles(level: ServerLevel, pos: BlockPos, yawDegrees: Float, scale: Float, tintRgb: Int) {
        val center = Vec3.atCenterOf(pos)
        val yawRad = Math.toRadians(yawDegrees.toDouble())
        val normal = Vec3(-kotlin.math.sin(yawRad), 0.0, kotlin.math.cos(yawRad))
        val tangent = Vec3(kotlin.math.cos(yawRad), 0.0, kotlin.math.sin(yawRad))
        val maxSide = 0.62 * scale
        val maxHeight = 0.78 * scale
        val random = level.random
        val particleRgb = mixRgb(tintRgb, 0xFFFFFF, 0.18f)
        val particleColour = Vector3f(
            ((particleRgb shr 16) and 0xFF) / 255.0f,
            ((particleRgb shr 8) and 0xFF) / 255.0f,
            (particleRgb and 0xFF) / 255.0f
        )
        val particle = DustParticleOptions(particleColour, 1.05f)

        repeat(FLOW_PARTICLES_PER_BURST) {
            val side = (random.nextDouble() * 2.0 - 1.0) * maxSide
            val height = (random.nextDouble() * 2.0 - 1.0) * (maxHeight * 0.92)
            val depth = (random.nextDouble() * 2.0 - 1.0) * (0.30 * scale)

            val spawn = center
                .add(tangent.scale(side))
                .add(normal.scale(depth))
                .add(0.0, height, 0.0)

            val towardPlane = normal.scale(-depth * 0.62)
            val alongFace = tangent.scale(-side * 0.0016)
            val verticalDrift = Vec3(0.0, (random.nextDouble() * 2.0 - 1.0) * 0.0009, 0.0)
            val shimmer = Vec3(
                (random.nextDouble() * 2.0 - 1.0) * 0.0012,
                (random.nextDouble() * 2.0 - 1.0) * 0.0004,
                (random.nextDouble() * 2.0 - 1.0) * 0.0012
            )
            val velocity = towardPlane.add(alongFace).add(verticalDrift).add(shimmer)

            // Drift stream follows the portal's active tint instead of vanilla reverse-portal purple.
            level.sendParticles(particle, spawn.x, spawn.y, spawn.z, 0, velocity.x * 0.55, velocity.y * 0.55, velocity.z * 0.55, 1.0)
        }
    }

    private fun removeSingleThresholdNow(level: ServerLevel) {
        if (level.getBlockState(worldPosition).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(level, worldPosition)
            level.removeBlock(worldPosition, false)
        }
    }

    private fun updateThresholdBlockState(level: ServerLevel, threshold: Boolean) {
        val state = level.getBlockState(worldPosition)
        if (state.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK || !state.hasProperty(CorridorPortalBlock.THRESHOLD)) {
            return
        }

        if (state.getValue(CorridorPortalBlock.THRESHOLD) != threshold) {
            level.setBlock(worldPosition, state.setValue(CorridorPortalBlock.THRESHOLD, threshold), 3)
        }
    }

    private fun clearOwnershipReference(level: ServerLevel) {
        val owner = ownerUuid ?: return
        PortalOwnershipStore.get(level.server).removeIfContains(
            owner,
            PortalOwnershipStore.PortalEndpoint(level.dimension().location().toString(), worldPosition)
        )
    }
}
