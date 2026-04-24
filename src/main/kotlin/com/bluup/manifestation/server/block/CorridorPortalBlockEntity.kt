package com.bluup.manifestation.server.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
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

    fun serverTick(level: ServerLevel) {
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
            startPairCollapse(level)
        }
    }

    fun tryTeleport(level: ServerLevel, entity: Entity, state: BlockState) {
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

        private const val TELEPORT_COOLDOWN_TICKS = 20L
        private const val EXIT_OFFSET = 0.80
        private const val TICKS_PER_DRAIN_STEP = 20L
        private const val MEDIA_DRAIN_PER_STEP = 10L
        private const val OPEN_ANIM_TICKS = 12L
        private const val CLOSE_ANIM_TICKS = 12L

        private fun normalFromYaw(yawDegrees: Float): Vec3 {
            val radians = Math.toRadians(yawDegrees.toDouble())
            return Vec3(-kotlin.math.sin(radians), 0.0, kotlin.math.cos(radians))
        }

        private fun smoothstep(t: Float): Float = t * t * (3.0f - 2.0f * t)
    }

    private fun isSustainDriver(level: ServerLevel): Boolean {
        val targetDim = targetDimensionId ?: return false
        val target = targetPos ?: return false
        val selfKey = level.dimension().location().toString() + ":" + worldPosition.asLong()
        val targetKey = targetDim + ":" + target.asLong()
        return selfKey <= targetKey
    }

    private fun startPairCollapse(level: ServerLevel) {
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
}
