package com.bluup.manifestation.server.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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
    private var previewPositive: List<String>? = null
    private var previewNegative: List<String>? = null

    private val cooldownUntilByEntity: MutableMap<UUID, Long> = ConcurrentHashMap()

    fun linkTo(
        level: ServerLevel,
        target: BlockPos,
        targetDimension: String,
        owner: UUID?,
        mediaBudget: Long,
        scale: Float
    ) {
        targetDimensionId = targetDimension
        targetPos = target.immutable()
        ownerUuid = owner
        sustainMediaRemaining = mediaBudget.coerceAtLeast(0L)
        lastSustainDrainGameTime = level.gameTime
        openedAtGameTime = level.gameTime
        collapseStartedAtGameTime = -1L
        renderScale = scale.coerceIn(0.1f, 3.0f)

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDimension))
        val targetLevel = level.server.getLevel(targetKey)
        if (targetLevel != null) {
            val axis = blockState.getValue(CorridorPortalBlock.AXIS)
            previewPositive = samplePreviewGrid(targetLevel, target, axis, 1)
            previewNegative = samplePreviewGrid(targetLevel, target, axis, -1)
        } else {
            previewPositive = null
            previewNegative = null
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

    fun getRenderPreviewPositive(): List<String>? = previewPositive

    fun getRenderPreviewNegative(): List<String>? = previewNegative

    fun getRenderScale(): Float = renderScale

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

        // Prime destination chunk so stepping through can succeed without requiring pre-loaded chunks.
        targetLevel.getChunk(target.x shr 4, target.z shr 4)

        val targetState = targetLevel.getBlockState(target)
        if (targetState.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return
        }

        val targetPortal = targetLevel.getBlockEntity(target) as? CorridorPortalBlockEntity ?: return

        val sourceNormal = normalFromAxis(state.getValue(CorridorPortalBlock.AXIS))
        val sourceCenter = Vec3.atCenterOf(worldPosition)
        val toEntity = entity.position().subtract(sourceCenter)
        val side = if (toEntity.dot(sourceNormal) >= 0.0) 1.0 else -1.0

        val targetNormal = normalFromAxis(targetState.getValue(CorridorPortalBlock.AXIS))
        val targetCenter = Vec3.atCenterOf(target)
        val relativeY = (entity.y - worldPosition.y).coerceIn(0.05, 1.75)
        val exitFacing = targetNormal.scale(side)
        val exitYaw = ((Mth.atan2(exitFacing.z, exitFacing.x) * (180.0 / Math.PI)) - 90.0).toFloat()
        val exitPos = targetCenter
            .add(targetNormal.scale(side * EXIT_OFFSET))
            .add(0.0, relativeY - 0.5, 0.0)

        val newCooldown = now + TELEPORT_COOLDOWN_TICKS
        cooldownUntilByEntity[uuid] = newCooldown
        targetPortal.cooldownUntilByEntity[uuid] = newCooldown

        if (entity is ServerPlayer) {
            entity.teleportTo(targetLevel, exitPos.x, exitPos.y, exitPos.z, exitYaw, entity.xRot)
            entity.setYHeadRot(exitYaw)
            entity.setYBodyRot(exitYaw)
        } else {
            if (targetLevel == level) {
                entity.teleportTo(exitPos.x, exitPos.y, exitPos.z)
                entity.yRot = exitYaw
                entity.setYHeadRot(exitYaw)
                entity.setYBodyRot(exitYaw)
            } else {
                return
            }
        }
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
        previewPositive = if (tag.contains(TAG_PREVIEW_POSITIVE, Tag.TAG_LIST.toInt())) {
            readStringList(tag.getList(TAG_PREVIEW_POSITIVE, Tag.TAG_STRING.toInt()))
        } else {
            null
        }
        previewNegative = if (tag.contains(TAG_PREVIEW_NEGATIVE, Tag.TAG_LIST.toInt())) {
            readStringList(tag.getList(TAG_PREVIEW_NEGATIVE, Tag.TAG_STRING.toInt()))
        } else {
            null
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
        val posPreview = previewPositive
        if (!posPreview.isNullOrEmpty()) {
            tag.put(TAG_PREVIEW_POSITIVE, writeStringList(posPreview))
        }
        val negPreview = previewNegative
        if (!negPreview.isNullOrEmpty()) {
            tag.put(TAG_PREVIEW_NEGATIVE, writeStringList(negPreview))
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
        private const val TAG_PREVIEW_POSITIVE = "PreviewPositive"
        private const val TAG_PREVIEW_NEGATIVE = "PreviewNegative"

        private const val TELEPORT_COOLDOWN_TICKS = 20L
        private const val EXIT_OFFSET = 0.80
        private const val TICKS_PER_DRAIN_STEP = 20L
        private const val MEDIA_DRAIN_PER_STEP = 10L
        private const val OPEN_ANIM_TICKS = 12L
        private const val CLOSE_ANIM_TICKS = 12L

        private fun normalFromAxis(axis: Direction.Axis): Vec3 {
            return when (axis) {
                Direction.Axis.X -> Vec3(1.0, 0.0, 0.0)
                Direction.Axis.Z -> Vec3(0.0, 0.0, 1.0)
                else -> Vec3(0.0, 0.0, 1.0)
            }
        }

        private fun smoothstep(t: Float): Float = t * t * (3.0f - 2.0f * t)

        private fun writeStringList(values: List<String>): ListTag {
            val out = ListTag()
            for (value in values) {
                out.add(StringTag.valueOf(value))
            }
            return out
        }

        private fun readStringList(values: ListTag): List<String> {
            val out = ArrayList<String>(values.size)
            for (i in 0 until values.size) {
                out.add(values.getString(i))
            }
            return out
        }
    }

    private fun samplePreviewGrid(
        targetLevel: ServerLevel,
        target: BlockPos,
        axis: Direction.Axis,
        normalStep: Int
    ): List<String> {
        val normalX: Int
        val normalZ: Int
        val rightX: Int
        val rightZ: Int

        if (axis == Direction.Axis.X) {
            normalX = normalStep
            normalZ = 0
            rightX = 0
            rightZ = if (normalStep > 0) -1 else 1
        } else {
            normalX = 0
            normalZ = normalStep
            rightX = if (normalStep > 0) 1 else -1
            rightZ = 0
        }

        val out = ArrayList<String>(9)
        for (row in 0..2) {
            val yOffset = row - 1
            for (col in 0..2) {
                val rightOffset = col - 1
                val samplePos = target.offset(
                    normalX + rightX * rightOffset,
                    yOffset,
                    normalZ + rightZ * rightOffset
                )

                var sampleState = targetLevel.getBlockState(samplePos)
                if (sampleState.block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                    sampleState = targetLevel.getBlockState(samplePos.offset(normalX, 0, normalZ))
                }

                val id = BuiltInRegistries.BLOCK.getKey(sampleState.block)
                out.add(id.toString())
            }
        }
        return out
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
            level.removeBlock(worldPosition, false)
        }

        if (target != null && targetDim != null) {
            val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(targetDim))
            val targetLevel = level.server.getLevel(targetKey)
            if (targetLevel != null) {
                if (targetLevel.getBlockState(target).block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                    targetLevel.removeBlock(target, false)
                }
            }
        }
    }
}
