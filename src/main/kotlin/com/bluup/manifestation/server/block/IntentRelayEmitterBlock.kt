package com.bluup.manifestation.server.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.IntegerProperty

class IntentRelayEmitterBlock(properties: Properties) : FaceAttachedHorizontalDirectionalBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACE, FACING, POWER)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: net.minecraft.world.phys.shapes.CollisionContext) =
        net.minecraft.world.phys.shapes.Shapes.empty()

    override fun isSignalSource(state: BlockState): Boolean = state.getValue(POWER) > 0

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        state.getValue(POWER)

    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        state.getValue(POWER)

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int = state.getValue(POWER)

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (level.getBlockState(pos).block != this) {
            return
        }

        level.removeBlock(pos, false)
        level.updateNeighborsAt(pos, this)
        level.updateNeighborsAt(attachedPos(pos, state), this)
    }

    private fun attachedPos(pos: BlockPos, state: BlockState): BlockPos = when (state.getValue(FACE)) {
        AttachFace.FLOOR -> pos.below()
        AttachFace.CEILING -> pos.above()
        AttachFace.WALL -> pos.relative(state.getValue(FACING).opposite)
    }

    companion object {
        val POWER: IntegerProperty = IntegerProperty.create("power", 0, 15)
    }
}