package com.bluup.manifestation.server.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class CorridorPortalBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(AXIS, net.minecraft.core.Direction.Axis.Z))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AXIS)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = when (state.getValue(AXIS)) {
        net.minecraft.core.Direction.Axis.X -> X_SHAPE
        net.minecraft.core.Direction.Axis.Z -> Z_SHAPE
        else -> Z_SHAPE
    }

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = Shapes.empty()

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level.isClientSide) {
            return
        }

        val server = level as? ServerLevel ?: return
        val portal = server.getBlockEntity(pos) as? CorridorPortalBlockEntity ?: return
        portal.tryTeleport(server, entity, state)
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) {
            return null
        }

        return createTickerHelper(type, ManifestationBlocks.CORRIDOR_PORTAL_BLOCK_ENTITY) { tickLevel, _, _, be ->
            val serverLevel = tickLevel as? ServerLevel ?: return@createTickerHelper
            be.serverTick(serverLevel)
        }
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CorridorPortalBlockEntity(pos, state)

    companion object {
        @JvmField
        val AXIS: EnumProperty<net.minecraft.core.Direction.Axis> = BlockStateProperties.HORIZONTAL_AXIS

        private const val HALF_THICKNESS = 0.75
        private val X_SHAPE: VoxelShape = box(8.0 - HALF_THICKNESS, 0.0, 0.0, 8.0 + HALF_THICKNESS, 16.0, 16.0)
        private val Z_SHAPE: VoxelShape = box(0.0, 0.0, 8.0 - HALF_THICKNESS, 16.0, 16.0, 8.0 + HALF_THICKNESS)
    }
}
