package com.bluup.manifestation.server.block

import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.Manifestation
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

class IntentRelayBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.INTENT_RELAY_BLOCK_ENTITY, pos, state) {

    private var targetDimensionId: String? = null
    private var targetPos: BlockPos? = null
    private var ownerUuid: UUID? = null
    private var nextUseGameTime: Long = 0L
    private var redstoneStrength: Int = 0
    private var redstoneMode: Boolean = false
    private var displayedItem: ItemStack = ItemStack.EMPTY

    // Active visual lifecycle.
    private var activeFallbackUntilGameTime: Long = 0L
    private var deactivateAfterAnimationGameTime: Long = 0L
    private var trackingEnabled: Boolean = false
    private var trackedTargetDimensionId: String? = null
    private var trackedTargetPos: BlockPos? = null
    private var trackedActiveStateKey: String? = null

    fun hasTarget(): Boolean = targetDimensionId != null && targetPos != null

    fun linkedTargetPos(): BlockPos? = targetPos?.immutable()

    fun linkedTargetDimensionId(): String? = targetDimensionId

    fun isRedstoneMode(): Boolean = redstoneMode

    fun setDisplayedItem(stack: ItemStack) {
        val normalized = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (ItemStack.isSameItemSameTags(displayedItem, normalized)) {
            return
        }

        displayedItem = normalized
        markUpdated()
    }

    fun displayedIconStack(): ItemStack {
        return if (redstoneMode) ItemStack(Items.REDSTONE) else displayedItem
    }

    fun setTarget(
        level: ServerLevel,
        newTargetPos: BlockPos,
        owner: UUID?,
        modeRedstoneStrength: Int?
    ) {
        targetDimensionId = level.dimension().location().toString()
        targetPos = newTargetPos.immutable()
        ownerUuid = owner
        redstoneMode = modeRedstoneStrength != null
        redstoneStrength = modeRedstoneStrength?.coerceIn(0, 15) ?: 0
        setRelayModeVisual(level, redstoneMode)
        markUpdated()
    }

    fun clearTarget() {
        targetDimensionId = null
        targetPos = null
        ownerUuid = null
        redstoneMode = false
        redstoneStrength = 0
        val serverLevel = level as? ServerLevel
        if (serverLevel != null) {
            setRelayModeVisual(serverLevel, false)
        }
        markUpdated()
    }

    fun forwardIntent(level: ServerLevel, player: ServerPlayer, hand: InteractionHand): InteractionResult {
        val dimId = targetDimensionId ?: return InteractionResult.PASS
        val pos = targetPos ?: return InteractionResult.PASS

        if (level.gameTime < nextUseGameTime) {
            return InteractionResult.CONSUME
        }

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(dimId))
        val targetLevel = player.server.getLevel(targetKey) ?: return InteractionResult.FAIL

        val maxRange = ManifestationConfig.intentRelayMaxRangeBlocks()
        if (maxRange > 0) {
            val limitSq = maxRange.toDouble() * maxRange.toDouble()
            if (worldPosition.distSqr(pos) > limitSq) {
                return InteractionResult.FAIL
            }
        }

        if (redstoneMode) {
            val lockoutTicks = maxOf(MIN_ACTIVATED_STATE_TICKS, ManifestationConfig.intentRelayCooldownTicks())
            if (!beginRedstonePulse(targetLevel, pos, lockoutTicks, redstoneStrength)) {
                return InteractionResult.FAIL
            }
            val outward = outwardDirection(blockState)
            ManifestationServer.sendIntentShifterRunes(level, worldPosition, outward, lockoutTicks)
            nextUseGameTime = level.gameTime + lockoutTicks
            beginActivatedState(level, dimId, pos, targetLevel.getBlockState(pos), targetLevel.getBlockState(pos), lockoutTicks)
            setChanged()
            return InteractionResult.CONSUME
        }

        val targetState = targetLevel.getBlockState(pos)
        if (targetState.isAir) {
            return InteractionResult.PASS
        }

        val stateBeforeUse = targetState

        val hit = BlockHitResult(
            Vec3.atCenterOf(pos),
            player.direction.opposite,
            pos,
            false
        )

        val result = IntentRelayRuntime.runForwarding {
            targetState.use(targetLevel, player, hand, hit)
        } ?: InteractionResult.FAIL

        if (result.consumesAction()) {
            val lockoutTicks = maxOf(MIN_ACTIVATED_STATE_TICKS, ManifestationConfig.intentRelayCooldownTicks())
            val stateAfterUse = targetLevel.getBlockState(pos)
            val outward = outwardDirection(blockState)
            ManifestationServer.sendIntentShifterRunes(level, worldPosition, outward, lockoutTicks)
            nextUseGameTime = level.gameTime + lockoutTicks
            beginActivatedState(level, dimId, pos, stateBeforeUse, stateAfterUse, lockoutTicks)
            setChanged()
        }

        return result
    }

    fun onScheduledTick(level: ServerLevel) {
        if (!isRelayActive(level)) {
            return
        }

        if (shouldRemainActive(level)) {
            level.scheduleTick(worldPosition, blockState.block, 1)
        } else {
            setRelayActive(level, false)
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)

        targetDimensionId = if (tag.contains(TAG_TARGET_DIMENSION)) tag.getString(TAG_TARGET_DIMENSION) else null
        targetPos = if (tag.contains(TAG_TARGET_POS)) BlockPos.of(tag.getLong(TAG_TARGET_POS)) else null
        ownerUuid = if (tag.hasUUID(TAG_OWNER_UUID)) tag.getUUID(TAG_OWNER_UUID) else null
        nextUseGameTime = tag.getLong(TAG_NEXT_USE_TIME)
        redstoneMode = tag.getBoolean(TAG_REDSTONE_MODE)
        redstoneStrength = tag.getInt(TAG_REDSTONE_STRENGTH).coerceIn(0, 15)
        displayedItem = if (tag.contains(TAG_DISPLAYED_ITEM)) {
            try {
                val loaded = ItemStack.of(tag.getCompound(TAG_DISPLAYED_ITEM))
                if (loaded.isEmpty) ItemStack.EMPTY else loaded.copyWithCount(1)
            } catch (t: Throwable) {
                Manifestation.LOGGER.warn("Manifestation: failed to load relay display item at {}", worldPosition, t)
                ItemStack.EMPTY
            }
        } else {
            ItemStack.EMPTY
        }

        activeFallbackUntilGameTime = tag.getLong(TAG_ACTIVE_FALLBACK_UNTIL)
        deactivateAfterAnimationGameTime = tag.getLong(TAG_DEACTIVATE_AFTER_ANIMATION)
        trackingEnabled = tag.getBoolean(TAG_TRACKING_ENABLED)
        trackedTargetDimensionId = if (tag.contains(TAG_TRACKED_TARGET_DIMENSION)) {
            tag.getString(TAG_TRACKED_TARGET_DIMENSION)
        } else {
            null
        }
        trackedTargetPos = if (tag.contains(TAG_TRACKED_TARGET_POS)) {
            BlockPos.of(tag.getLong(TAG_TRACKED_TARGET_POS))
        } else {
            null
        }
        trackedActiveStateKey = if (tag.contains(TAG_TRACKED_ACTIVE_STATE_KEY)) {
            tag.getString(TAG_TRACKED_ACTIVE_STATE_KEY)
        } else {
            null
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)

        val dimId = targetDimensionId
        if (dimId != null) {
            tag.putString(TAG_TARGET_DIMENSION, dimId)
        }

        val pos = targetPos
        if (pos != null) {
            tag.putLong(TAG_TARGET_POS, pos.asLong())
        }

        val owner = ownerUuid
        if (owner != null) {
            tag.putUUID(TAG_OWNER_UUID, owner)
        }

        tag.putLong(TAG_NEXT_USE_TIME, nextUseGameTime)
        tag.putBoolean(TAG_REDSTONE_MODE, redstoneMode)
        tag.putInt(TAG_REDSTONE_STRENGTH, redstoneStrength)
        if (!displayedItem.isEmpty) {
            tag.put(TAG_DISPLAYED_ITEM, displayedItem.save(CompoundTag()))
        }
        tag.putLong(TAG_ACTIVE_FALLBACK_UNTIL, activeFallbackUntilGameTime)
        tag.putLong(TAG_DEACTIVATE_AFTER_ANIMATION, deactivateAfterAnimationGameTime)
        tag.putBoolean(TAG_TRACKING_ENABLED, trackingEnabled)

        val trackedDim = trackedTargetDimensionId
        if (trackedDim != null) {
            tag.putString(TAG_TRACKED_TARGET_DIMENSION, trackedDim)
        }
        val trackedPos = trackedTargetPos
        if (trackedPos != null) {
            tag.putLong(TAG_TRACKED_TARGET_POS, trackedPos.asLong())
        }
        val trackedState = trackedActiveStateKey
        if (trackedState != null) {
            tag.putString(TAG_TRACKED_ACTIVE_STATE_KEY, trackedState)
        }
    }

    override fun getUpdateTag(): CompoundTag {
        return saveWithoutMetadata()
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> {
        return ClientboundBlockEntityDataPacket.create(this)
    }

    override fun setLevel(level: net.minecraft.world.level.Level) {
        super.setLevel(level)
        // Do not mutate blockstate during block-entity attach/chunk load.
        // Calling setBlockAndUpdate here can synchronously request chunks and stall
        // integrated server spawn preparation on existing worlds.
    }

    private fun beginActivatedState(
        level: ServerLevel,
        dimId: String,
        pos: BlockPos,
        before: BlockState,
        after: BlockState,
        fallbackTicks: Int
    ) {
        activeFallbackUntilGameTime = level.gameTime + fallbackTicks
        deactivateAfterAnimationGameTime = 0L

        trackingEnabled = before != after && !isStickyToggleState(after)
        trackedTargetDimensionId = if (trackingEnabled) dimId else null
        trackedTargetPos = if (trackingEnabled) pos.immutable() else null
        trackedActiveStateKey = if (trackingEnabled) stateKey(after) else null

        setRelayActive(level, true)
        level.scheduleTick(worldPosition, blockState.block, 1)
    }

    private fun beginRedstonePulse(
        targetLevel: ServerLevel,
        targetSupportPos: BlockPos,
        fallbackTicks: Int,
        power: Int
    ): Boolean {
        var emitted = false
        for (outward in Direction.values()) {
            val emitterPos = targetSupportPos.relative(outward)
            val existingState = targetLevel.getBlockState(emitterPos)
            if (!existingState.isAir && existingState.block != ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK) {
                continue
            }

            val emitterState = redstoneEmitterStateFor(outward)
                .setValue(IntentRelayEmitterBlock.POWER, power.coerceIn(0, 15))
            if (!emitterState.canSurvive(targetLevel, emitterPos)) {
                continue
            }
            if (!targetLevel.setBlock(emitterPos, emitterState, Block.UPDATE_ALL)) {
                continue
            }

            targetLevel.scheduleTick(emitterPos, ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK, fallbackTicks)
            targetLevel.updateNeighborsAt(emitterPos, ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK)
            emitted = true
        }
        if (!emitted) {
            return false
        }
        targetLevel.updateNeighborsAt(targetSupportPos, ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK)

        val relayLevel = level as? ServerLevel ?: return false

        activeFallbackUntilGameTime = relayLevel.gameTime + fallbackTicks
        deactivateAfterAnimationGameTime = 0L
        trackingEnabled = false
        trackedTargetDimensionId = null
        trackedTargetPos = null
        trackedActiveStateKey = null

        setRelayState(relayLevel, true, 0)
        relayLevel.scheduleTick(worldPosition, blockState.block, 1)
        return true
    }

    private fun setRelayModeVisual(level: ServerLevel, mode: Boolean) {
        val state = level.getBlockState(worldPosition)
        if (!state.hasProperty(IntentRelayBlock.REDSTONE_MODE)) {
            return
        }

        val next = state.setValue(IntentRelayBlock.REDSTONE_MODE, mode)
        if (state != next) {
            level.setBlockAndUpdate(worldPosition, next)
        }
    }

    private fun shouldRemainActive(level: ServerLevel): Boolean {
        if (level.gameTime < activeFallbackUntilGameTime) {
            deactivateAfterAnimationGameTime = 0L
            return true
        }

        if (isTrackedStateStillActive(level)) {
            deactivateAfterAnimationGameTime = 0L
            return true
        }

        if (deactivateAfterAnimationGameTime == 0L) {
            deactivateAfterAnimationGameTime = lastTickBeforeNextAnimationLoop(level.gameTime)
            if (level.gameTime <= deactivateAfterAnimationGameTime) {
                setChanged()
                return true
            }
        }

        return level.gameTime <= deactivateAfterAnimationGameTime
    }

    private fun setRelayActive(level: ServerLevel, active: Boolean) {
        setRelayState(level, active, if (active && redstoneMode) redstoneStrength else 0)
    }

    private fun setRelayState(level: ServerLevel, active: Boolean, power: Int) {
        val state = level.getBlockState(worldPosition)
        if (!state.hasProperty(IntentRelayBlock.ACTIVE)) {
            return
        }

        val clampedPower = power.coerceIn(0, 15)
        val nextState = state
            .setValue(IntentRelayBlock.ACTIVE, active)
            .setValue(IntentRelayBlock.POWER, clampedPower)
        if (state != nextState) {
            level.setBlockAndUpdate(worldPosition, nextState)
        }

        if (!active) {
            clearTrackingState()
        }
    }

    private fun isRelayActive(level: ServerLevel): Boolean {
        val state = level.getBlockState(worldPosition)
        return state.hasProperty(IntentRelayBlock.ACTIVE) && state.getValue(IntentRelayBlock.ACTIVE)
    }

    private fun clearTrackingState() {
        activeFallbackUntilGameTime = 0L
        deactivateAfterAnimationGameTime = 0L
        trackingEnabled = false
        trackedTargetDimensionId = null
        trackedTargetPos = null
        trackedActiveStateKey = null
    }

    private fun isTrackedStateStillActive(level: ServerLevel): Boolean {
        if (!trackingEnabled) {
            return false
        }

        val dimId = trackedTargetDimensionId ?: return false
        val pos = trackedTargetPos ?: return false
        val stateKey = trackedActiveStateKey ?: return false

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(dimId))
        val targetLevel = level.server.getLevel(targetKey) ?: return false
        if (!targetLevel.isLoaded(pos)) {
            return false
        }

        return stateKey(targetLevel.getBlockState(pos)) == stateKey
    }

    private fun lastTickBeforeNextAnimationLoop(gameTime: Long): Long {
        val cycleLength = ACTIVATED_ANIMATION_TICKS.toLong()
        val remainder = Math.floorMod(gameTime, cycleLength)
        val ticksUntilLoop = if (remainder == 0L) cycleLength else (cycleLength - remainder)
        return gameTime + ticksUntilLoop - 1L
    }

    private fun stateKey(state: BlockState): String = state.toString()

    private fun isStickyToggleState(state: BlockState): Boolean {
        // Lever-like targets keep a stable on/off state, so live-state tracking
        // would hold the shifter active indefinitely until manually toggled back.
        return state.block is LeverBlock
    }

    private fun outwardDirection(state: BlockState): Direction {
        val face = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)
        return when (face) {
            AttachFace.FLOOR -> Direction.UP
            AttachFace.CEILING -> Direction.DOWN
            AttachFace.WALL -> state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)
        }
    }

    private fun markUpdated() {
        setChanged()
        val world = level as? ServerLevel ?: return
        world.sendBlockUpdated(worldPosition, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    companion object {
        private const val MIN_ACTIVATED_STATE_TICKS = 20
        private const val ACTIVATED_ANIMATION_TICKS = 75

        private const val TAG_TARGET_DIMENSION = "TargetDimension"
        private const val TAG_TARGET_POS = "TargetPos"
        private const val TAG_OWNER_UUID = "OwnerUuid"
        private const val TAG_NEXT_USE_TIME = "NextUseGameTime"
        private const val TAG_REDSTONE_MODE = "RedstoneMode"
        private const val TAG_REDSTONE_STRENGTH = "RedstoneStrength"
        private const val TAG_DISPLAYED_ITEM = "DisplayedItem"
        private const val TAG_ACTIVE_FALLBACK_UNTIL = "ActiveFallbackUntilGameTime"
        private const val TAG_DEACTIVATE_AFTER_ANIMATION = "DeactivateAfterAnimationGameTime"
        private const val TAG_TRACKING_ENABLED = "TrackingEnabled"
        private const val TAG_TRACKED_TARGET_DIMENSION = "TrackedTargetDimension"
        private const val TAG_TRACKED_TARGET_POS = "TrackedTargetPos"
        private const val TAG_TRACKED_ACTIVE_STATE_KEY = "TrackedActiveStateKey"

        fun redstoneEmitterStateFor(outward: Direction): BlockState {
            val base = ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK.defaultBlockState()
            return when (outward) {
                Direction.UP -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.NORTH)

                Direction.DOWN -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.CEILING)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, Direction.NORTH)

                else -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, outward)
            }
        }
    }
}
