package com.bluup.manifestation.server.block

import com.bluup.manifestation.server.ManifestationConfig
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack

class IntentRelayBlock(properties: Properties) : FaceAttachedHorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, net.minecraft.core.Direction.NORTH)
                .setValue(ACTIVE, false)
                .setValue(REDSTONE_MODE, false)
                .setValue(POWER, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACE, FACING, ACTIVE, REDSTONE_MODE, POWER)
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape {
        return when (state.getValue(FACE)) {
            AttachFace.FLOOR -> FLOOR_SHAPE
            AttachFace.CEILING -> CEILING_SHAPE
            AttachFace.WALL -> when (state.getValue(FACING)) {
                net.minecraft.core.Direction.NORTH -> NORTH_WALL_SHAPE
                net.minecraft.core.Direction.SOUTH -> SOUTH_WALL_SHAPE
                net.minecraft.core.Direction.WEST -> WEST_WALL_SHAPE
                net.minecraft.core.Direction.EAST -> EAST_WALL_SHAPE
                else -> Shapes.block()
            }
        }
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun isSignalSource(state: BlockState): Boolean = false

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int = 0

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int = 0

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = false

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int = 0

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        val serverLevel = level as? ServerLevel ?: return InteractionResult.PASS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val relay = serverLevel.getBlockEntity(pos) as? IntentRelayBlockEntity ?: return InteractionResult.PASS

        if (serverPlayer.isShiftKeyDown) {
            if (!relay.isRedstoneMode()) {
                val held = serverPlayer.getItemInHand(hand)
                val shown = if (held.isEmpty) ItemStack.EMPTY else held.copyWithCount(1)
                relay.setDisplayedItem(shown)
            }
            return InteractionResult.CONSUME
        }

        val result = relay.forwardIntent(serverLevel, serverPlayer, hand)
        return if (result.consumesAction()) InteractionResult.CONSUME else result
    }

    override fun stepOn(level: Level, pos: BlockPos, state: BlockState, entity: Entity) {
        super.stepOn(level, pos, state, entity)

        if (level.isClientSide) {
            return
        }
        if (!ManifestationConfig.intentRelayStepTriggerEnabled()) {
            return
        }
        if (state.getValue(FACE) != AttachFace.FLOOR) {
            return
        }

        val serverLevel = level as? ServerLevel ?: return
        val serverPlayer = entity as? ServerPlayer ?: return
        val relay = serverLevel.getBlockEntity(pos) as? IntentRelayBlockEntity ?: return

        relay.forwardIntent(serverLevel, serverPlayer, InteractionHand.MAIN_HAND)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return IntentRelayBlockEntity(pos, state)
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val relay = level.getBlockEntity(pos) as? IntentRelayBlockEntity
        if (relay != null) {
            relay.onScheduledTick(level)
            return
        }

        if (state.getValue(ACTIVE)) {
            level.setBlock(pos, state.setValue(ACTIVE, false).setValue(POWER, 0), Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        val ACTIVE: BooleanProperty = BooleanProperty.create("active")
        val REDSTONE_MODE: BooleanProperty = BooleanProperty.create("redstone_mode")
        val POWER: IntegerProperty = IntegerProperty.create("power", 0, 15)

        private const val DECAL_DEPTH = 0.2
        private val FLOOR_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, DECAL_DEPTH, 16.0)
        private val CEILING_SHAPE: VoxelShape = box(0.0, 16.0 - DECAL_DEPTH, 0.0, 16.0, 16.0, 16.0)
        private val NORTH_WALL_SHAPE: VoxelShape = box(0.0, 0.0, 16.0 - DECAL_DEPTH, 16.0, 16.0, 16.0)
        private val SOUTH_WALL_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 16.0, DECAL_DEPTH)
        private val WEST_WALL_SHAPE: VoxelShape = box(16.0 - DECAL_DEPTH, 0.0, 0.0, 16.0, 16.0, 16.0)
        private val EAST_WALL_SHAPE: VoxelShape = box(0.0, 0.0, 0.0, DECAL_DEPTH, 16.0, 16.0)
    }
}
