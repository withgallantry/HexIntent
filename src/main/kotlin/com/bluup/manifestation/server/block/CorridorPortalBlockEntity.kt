package com.bluup.manifestation.server.block

import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import com.bluup.manifestation.Manifestation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CorridorPortalBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.CORRIDOR_PORTAL_BLOCK_ENTITY, pos, state) {

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
    private val thresholdPatterns: MutableList<CompoundTag> = mutableListOf()

    private val cooldownUntilByEntity: MutableMap<UUID, Long> = ConcurrentHashMap()

    fun linkTo(
        level: ServerLevel,
        target: BlockPos,
        targetDimension: String,
        owner: UUID?,
        mediaBudget: Long,
        scale: Float,
        yawDegrees: Float
    ) {
        targetDimensionId = targetDimension
        targetPos = target.immutable()
        ownerUuid = owner
        sustainMediaRemaining = mediaBudget.coerceAtLeast(0L)
        lastSustainDrainGameTime = level.gameTime
        openedAtGameTime = level.gameTime
        collapseStartedAtGameTime = -1L
        renderScale = scale.coerceIn(0.1f, 3.0f)
        renderYawDegrees = Mth.wrapDegrees(yawDegrees)
        thresholdMode = false
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
        val now = world.gameTime + partialTick

        val open = if (openedAtGameTime <= 0L) {
            1.0f
        } else {
            smoothstep(((now - openedAtGameTime) / OPEN_ANIM_TICKS.toFloat()).coerceIn(0.0f, 1.0f))
        }

        val close = if (collapseStartedAtGameTime < 0L) {
            1.0f
        } else {
            1.0f - smoothstep(((now - collapseStartedAtGameTime) / CLOSE_ANIM_TICKS.toFloat()).coerceIn(0.0f, 1.0f))
        }

        return (open * close).coerceIn(0.0f, 1.0f)
    }

    fun collapseProgress(partialTick: Float): Float {
        val world = level ?: return 0.0f
        val start = collapseStartedAtGameTime
        if (start < 0L) {
            return 0.0f
        }

        val now = world.gameTime + partialTick
        return ((now - start) / CLOSE_ANIM_TICKS.toFloat()).coerceIn(0.0f, 1.0f)
    }

    fun getRenderTargetPos(): BlockPos? = targetPos

    fun getRenderTargetDimensionId(): String? = targetDimensionId

    fun getRenderScale(): Float = renderScale

    fun getRenderYawDegrees(): Float = renderYawDegrees

    fun isThresholdMode(): Boolean = thresholdMode

    fun serverTick(level: ServerLevel) {
        if (thresholdMode) {
            serverTickThreshold(level)
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

        if (level.gameTime % THRESHOLD_PARTICLE_INTERVAL_TICKS == 0L) {
            spawnThresholdAmbientParticles(level)
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

    fun tryTeleport(level: ServerLevel, entity: Entity, state: BlockState) {
        if (thresholdMode) {
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
            entity.teleportTo(targetLevel, exitPos.x, exitPos.y, exitPos.z, exitYaw, entity.xRot)
            entity.setYHeadRot(exitYaw)
            entity.setYBodyRot(exitYaw)
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
        runThresholdPatterns(level, entity)

        // Threshold mode is single-fire: once crossed, it begins dissolving.
        beginCollapse(level)
    }

    private fun runThresholdPatterns(level: ServerLevel, entity: Entity) {
        if (thresholdPatterns.isEmpty()) {
            return
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
            return
        }

        try {
            val env = ThresholdTriggerCastEnv(level, worldPosition, this)
            val vm = CastingVM.empty(env)
            vm.image = vm.image.copy(stack = listOf(EntityIota(entity)))
            vm.queueExecuteAndWrapIotas(iotas, level)
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn("Manifestation: threshold trigger execution failed at {}", worldPosition, t)
        }
    }

    fun extractThresholdMedia(cost: Long, simulate: Boolean): Long {
        if (cost <= 0L) {
            return 0L
        }

        val available = sustainMediaRemaining.coerceAtLeast(0L)
        val spent = minOf(cost, available)
        if (!simulate && spent > 0L) {
            sustainMediaRemaining = available - spent
            setChanged()
        }

        return cost - spent
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
        private const val TAG_THRESHOLD_PATTERNS = "ThresholdPatterns"

        private const val TELEPORT_COOLDOWN_TICKS = 20L
        private const val THRESHOLD_TRIGGER_COOLDOWN_TICKS = 10L
        private const val EXIT_OFFSET = 0.80
        private const val TICKS_PER_DRAIN_STEP = 20L
        private const val MEDIA_DRAIN_PER_STEP = 1L
        private const val THRESHOLD_MEDIA_DRAIN_PER_STEP = 1L
        private const val OPEN_ANIM_TICKS = 12L
        private const val CLOSE_ANIM_TICKS = 12L
        private const val FLOW_PARTICLE_INTERVAL_TICKS = 4L
        private const val THRESHOLD_PARTICLE_INTERVAL_TICKS = 5L
        private const val FLOW_PARTICLES_PER_BURST = 2

        private fun normalFromYaw(yawDegrees: Float): Vec3 {
            val radians = Math.toRadians(yawDegrees.toDouble())
            return Vec3(-kotlin.math.sin(radians), 0.0, kotlin.math.cos(radians))
        }

        private fun smoothstep(t: Float): Float = t * t * (3.0f - 2.0f * t)
    }

    private fun isSustainDriver(level: ServerLevel): Boolean {
        if (thresholdMode) {
            return true
        }

        val targetDim = targetDimensionId ?: return false
        val target = targetPos ?: return false
        val selfKey = level.dimension().location().toString() + ":" + worldPosition.asLong()
        val targetKey = targetDim + ":" + target.asLong()
        return selfKey <= targetKey
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

        if (level.getBlockState(worldPosition).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(level, worldPosition)
            level.removeBlock(worldPosition, false)
        }

        if (target != null && targetDim != null) {
            val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
            val targetLevel = level.server.getLevel(targetKey)
            if (targetLevel != null) {
                if (targetLevel.getBlockState(target).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
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

        val target = targetPos ?: return
        val targetDim = targetDimensionId ?: return

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
        val targetLevel = level.server.getLevel(targetKey) ?: return
        if (targetLevel.getBlockState(target).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(targetLevel, target)
            targetLevel.removeBlock(target, false)
        }
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
        spawnInflowParticles(level, worldPosition, renderYawDegrees, ownScale)

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
        spawnInflowParticles(targetLevel, target, targetYaw, targetScale)
    }

    private fun spawnInflowParticles(level: ServerLevel, pos: BlockPos, yawDegrees: Float, scale: Float) {
        val center = Vec3.atCenterOf(pos)
        val yawRad = Math.toRadians(yawDegrees.toDouble())
        val normal = Vec3(-kotlin.math.sin(yawRad), 0.0, kotlin.math.cos(yawRad))
        val tangent = Vec3(kotlin.math.cos(yawRad), 0.0, kotlin.math.sin(yawRad))
        val maxSide = 0.62 * scale
        val maxHeight = 0.78 * scale

        repeat(FLOW_PARTICLES_PER_BURST) {
            val useVerticalEdge = level.random.nextBoolean()
            val side = if (useVerticalEdge) {
                if (level.random.nextBoolean()) maxSide else -maxSide
            } else {
                (level.random.nextDouble() * 2.0 - 1.0) * maxSide
            }
            val height = if (useVerticalEdge) {
                (level.random.nextDouble() * 2.0 - 1.0) * (maxHeight * 0.95)
            } else {
                (maxHeight * 0.8) + (level.random.nextDouble() * maxHeight * 0.25)
            }
            val depth = (level.random.nextDouble() * 2.0 - 1.0) * 0.035

            val spawn = center
                .add(tangent.scale(side))
                .add(normal.scale(depth))
                .add(0.0, height, 0.0)

            val towardPlane = normal.scale(-depth * (6.0 + level.random.nextDouble() * 4.0))
            val lateral = tangent.scale((level.random.nextDouble() * 2.0 - 1.0) * 0.01)
            val downward = Vec3(0.0, -(0.035 + level.random.nextDouble() * 0.045), 0.0)
            val velocity = towardPlane.add(lateral).add(downward)

            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, spawn.x, spawn.y, spawn.z, 0, velocity.x, velocity.y, velocity.z, 1.0)
        }
    }

    private fun spawnThresholdAmbientParticles(level: ServerLevel) {
        val center = Vec3.atCenterOf(worldPosition)
        val scale = renderScale.coerceIn(0.1f, 3.0f).toDouble()
        val time = level.gameTime.toDouble()

        repeat(3) { i ->
            // Use a time-driven orbit so sparkles glide instead of popping randomly.
            val baseAngle = (Math.PI * 2.0 * i / 3.0)
            val angle = baseAngle + (time * 0.075)
            val radius = (0.34 + (0.06 * kotlin.math.sin(time * 0.06 + i))) * scale
            val x = center.x + kotlin.math.cos(angle) * radius
            val z = center.z + kotlin.math.sin(angle) * radius
            val y = center.y + (0.08 * scale) + (0.18 * kotlin.math.sin(time * 0.08 + (i * 1.7)))

            val vx = -kotlin.math.sin(angle) * 0.010
            val vz = kotlin.math.cos(angle) * 0.010
            val vy = 0.010 + (0.004 * kotlin.math.sin(time * 0.09 + i))

            // END_ROD lives longer and reads as a stable magical sparkle.
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, vx, vy, vz, 0.0)

            // Keep a subtle secondary shimmer without turning into visual noise.
            if (level.random.nextInt(3) == 0) {
                level.sendParticles(ParticleTypes.ENCHANT, x, y + 0.03, z, 1, vx * 0.5, vy * 0.7, vz * 0.5, 0.0)
            }
        }
    }

    private fun removeSingleThresholdNow(level: ServerLevel) {
        if (level.getBlockState(worldPosition).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            playCollapseEffects(level, worldPosition)
            level.removeBlock(worldPosition, false)
        }
    }
}
