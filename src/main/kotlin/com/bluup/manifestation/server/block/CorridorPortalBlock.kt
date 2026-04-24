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
import net.minecraft.world.phys.Vec3
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
    ): VoxelShape = if (state.getValue(AXIS) == net.minecraft.core.Direction.Axis.X) {
        INTERACTION_SHAPE_X
    } else {
        INTERACTION_SHAPE_Z
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

        if (portal.isThresholdMode()) {
            portal.tryTriggerThreshold(server, entity)
            return
        }

        // Keep portal interaction to a slim, yaw-aligned plane around the visual aperture.
        val scale = portal.getRenderScale().coerceIn(0.1f, 3.0f).toDouble()
        val center = Vec3.atCenterOf(pos)
        val entityCenter = entity.boundingBox.center
        val relative = entityCenter.subtract(center)

        val yawRad = Math.toRadians(portal.getRenderYawDegrees().toDouble())
        val normal = Vec3(-kotlin.math.sin(yawRad), 0.0, kotlin.math.cos(yawRad))
        val tangent = Vec3(kotlin.math.cos(yawRad), 0.0, kotlin.math.sin(yawRad))

        val halfThickness = 0.10
        val halfWidth = 0.52 * scale
        val halfHeight = 0.82 * scale

        val bb = entity.boundingBox
        val halfSizeX = bb.xsize * 0.5
        val halfSizeY = bb.ysize * 0.5
        val halfSizeZ = bb.zsize * 0.5

        // Project the entity volume onto each portal axis so touching the band with any body part counts.
        val normalReach = kotlin.math.abs(normal.x) * halfSizeX + kotlin.math.abs(normal.y) * halfSizeY + kotlin.math.abs(normal.z) * halfSizeZ
        val tangentReach = kotlin.math.abs(tangent.x) * halfSizeX + kotlin.math.abs(tangent.y) * halfSizeY + kotlin.math.abs(tangent.z) * halfSizeZ

        val depth = relative.dot(normal)
        val horizontal = relative.dot(tangent)
        val vertical = relative.y
        if (kotlin.math.abs(depth) > (halfThickness + normalReach)
            || kotlin.math.abs(horizontal) > (halfWidth + tangentReach)
            || kotlin.math.abs(vertical) > (halfHeight + halfSizeY)
        ) {
            return
        }

        portal.tryTeleport(server, entity, state)
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (state.block != newState.block) {
            val server = level as? ServerLevel
            val portal = server?.getBlockEntity(pos) as? CorridorPortalBlockEntity
            if (server != null && portal != null) {
                portal.breakLinkedCounterpartNow(server)
            }
        }

        super.onRemove(state, level, pos, newState, isMoving)
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

        private val INTERACTION_SHAPE_X: VoxelShape = box(0.0, 0.0, 7.0, 16.0, 16.0, 9.0)
        private val INTERACTION_SHAPE_Z: VoxelShape = box(7.0, 0.0, 0.0, 9.0, 16.0, 16.0)
    }
}
