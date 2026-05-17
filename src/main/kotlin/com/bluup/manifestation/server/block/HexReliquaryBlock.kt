package com.bluup.manifestation.server.block

import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.network.chat.Component
import net.minecraft.util.RandomSource

class HexReliquaryBlock(properties: Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(CURRENT, 0)
                .setValue(TARGET, 0)
                .setValue(FRAME, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CURRENT, TARGET, FRAME)
    }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = Shapes.block()

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity {
        return HexReliquaryBlockEntity(pos, state)
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

        if (state.getValue(FRAME) != 0) {
            return InteractionResult.CONSUME
        }

        val be = level.getBlockEntity(pos) as? HexReliquaryBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)

        if (held.isEmpty) {
            beginTransition(level as ServerLevel, pos, state, nextSlot(state.getValue(CURRENT)))
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7f, 1.2f)
            return InteractionResult.CONSUME
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(held) ?: return InteractionResult.PASS
        val serverLevel = level as ServerLevel
        val selected = state.getValue(CURRENT)
        val incoming = holder.readIota(serverLevel)

        if (incoming != null) {
            val label = if (held.hasCustomHoverName()) {
                held.hoverName.string.take(MAX_LABEL_LENGTH)
            } else {
                Component.translatable("block.manifestation.hex_reliquary.slot_label_default", selected + 1).string
            }

            be.setSlot(selected, incoming, label)
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)

            if (!held.hasCustomHoverName()) {
                player.displayClientMessage(
                    Component.translatable("message.manifestation.hex_reliquary.rename_hint", selected + 1),
                    true
                )
            }

            return InteractionResult.CONSUME
        }

        val stored = be.getSlotIota(selected, serverLevel) ?: return InteractionResult.PASS
        if (holder.writeIota(stored, true)) {
            holder.writeIota(stored, false)
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.BLOCKS, 0.8f, 1.0f)
            return InteractionResult.CONSUME
        }

        return InteractionResult.PASS
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val frame = state.getValue(FRAME)
        if (frame <= 0) {
            return
        }

        if (frame < FINAL_FRAME) {
            level.setBlock(pos, state.setValue(FRAME, frame + 1), Block.UPDATE_CLIENTS)
            level.scheduleTick(pos, this, TICK_RATE)
            return
        }

        val target = state.getValue(TARGET)
        level.setBlock(
            pos,
            state
                .setValue(CURRENT, target)
                .setValue(FRAME, 0),
            Block.UPDATE_CLIENTS
        )
    }

    private fun beginTransition(level: ServerLevel, pos: BlockPos, state: BlockState, target: Int) {
        level.setBlock(
            pos,
            state
                .setValue(TARGET, target)
                .setValue(FRAME, 1),
            Block.UPDATE_CLIENTS
        )
        level.scheduleTick(pos, this, TICK_RATE)
    }

    private fun nextSlot(current: Int): Int {
        return (current + 1) % SLOT_COUNT
    }

    companion object {
        private const val TICK_RATE = 2
        private const val FINAL_FRAME = 11
        private const val MAX_LABEL_LENGTH = 48

        const val SLOT_COUNT = 5
        @JvmField
        val CURRENT: IntegerProperty = IntegerProperty.create("current", 0, SLOT_COUNT - 1)
        @JvmField
        val TARGET: IntegerProperty = IntegerProperty.create("target", 0, SLOT_COUNT - 1)
        @JvmField
        val FRAME: IntegerProperty = IntegerProperty.create("frame", 0, FINAL_FRAME)
    }
}
