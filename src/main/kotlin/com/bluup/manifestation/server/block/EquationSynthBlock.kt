package com.bluup.manifestation.server.block

import com.bluup.manifestation.server.ManifestationServer
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class EquationSynthBlock(properties: Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FOCUS, false)
                .setValue(ACTIVE, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FOCUS, ACTIVE)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = Shapes.block()

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return EquationSynthBlockEntity(pos, state)
    }

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

        val be = level.getBlockEntity(pos) as? EquationSynthBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)

        if (!be.hasFocus()) {
            if (held.isEmpty) {
                return InteractionResult.PASS
            }
            if (!be.canAcceptFocus(held)) {
                level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.5f, 0.8f)
                return InteractionResult.CONSUME
            }

            be.setFocus(held.copyWithCount(1))
            held.shrink(1)
            level.setBlock(
                pos,
                state
                    .setValue(FOCUS, true)
                    .setValue(ACTIVE, be.hasPreviewEquation()),
                UPDATE_CLIENTS
            )
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)
            return InteractionResult.CONSUME
        }

        if (held.isEmpty) {
            val out = be.popFocus()
            player.setItemInHand(hand, out)
            level.setBlock(
                pos,
                state
                    .setValue(FOCUS, false)
                    .setValue(ACTIVE, false),
                UPDATE_CLIENTS
            )
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)
            return InteractionResult.CONSUME
        }

        if (!state.getValue(ACTIVE) && be.hasPreviewEquation()) {
            level.setBlock(pos, state.setValue(ACTIVE, true), UPDATE_CLIENTS)
        }

        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.CONSUME
        ManifestationServer.openEquationSynth(serverPlayer, pos)
        return InteractionResult.CONSUME
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (state.block != newState.block) {
            val be = level.getBlockEntity(pos) as? EquationSynthBlockEntity
            if (be != null) {
                val focus = be.popFocus()
                if (!focus.isEmpty) {
                    popResource(level, pos, focus)
                }
            }
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        @JvmField
        val FOCUS: BooleanProperty = BooleanProperty.create("focus")

        @JvmField
        val ACTIVE: BooleanProperty = BooleanProperty.create("active")
    }
}
