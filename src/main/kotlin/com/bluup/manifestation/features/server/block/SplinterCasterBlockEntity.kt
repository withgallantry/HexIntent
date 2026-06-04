package com.bluup.manifestation.server.block

import at.petrak.hexcasting.api.block.circle.BlockCircleComponent
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.entity.BlockEntity

class SplinterCasterBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.SPLINTER_CASTER_BLOCK_ENTITY, pos, state) {

    private var focus: ItemStack = ItemStack.EMPTY
    private var waitingForSplinter: Boolean = false

    fun hasFocus(): Boolean = !focus.isEmpty

    fun isWaitingForSplinter(): Boolean = waitingForSplinter

    fun setWaitingForSplinter(waiting: Boolean) {
        if (waitingForSplinter == waiting) {
            return
        }
        waitingForSplinter = waiting
        markUpdated()
    }

    fun getFocusCopy(): ItemStack = if (focus.isEmpty) ItemStack.EMPTY else focus.copy()

    fun setFocus(stack: ItemStack) {
        val normalized = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (ItemStack.isSameItemSameTags(focus, normalized)) {
            return
        }
        focus = normalized
        if (focus.isEmpty) {
            waitingForSplinter = false
        }
        markUpdated()
    }

    fun popFocus(): ItemStack {
        val out = focus
        focus = ItemStack.EMPTY
        waitingForSplinter = false
        markUpdated()
        return out
    }

    fun tickServer(level: ServerLevel) {
        val state = level.getBlockState(worldPosition)
        if (state.block !is SplinterCasterBlock) {
            return
        }

        if (!ManifestationConfig.splinterCasterEnabled()) {
            val removed = SplinterRuntime.removeAnchoredAt(
                level.server,
                level.dimension().location().toString(),
                worldPosition
            )
            if (removed > 0 || waitingForSplinter) {
                waitingForSplinter = false
                markUpdated()
            }
        }

        if (level.hasNeighborSignal(worldPosition)) {
            val removed = SplinterRuntime.removeAnchoredAt(
                level.server,
                level.dimension().location().toString(),
                worldPosition
            )
            if (removed > 0 || waitingForSplinter) {
                waitingForSplinter = false
                markUpdated()
            }
        }

        val active = SplinterRuntime.hasAnchoredSplinterAt(
            level.server,
            level.dimension().location().toString(),
            worldPosition
        )
        val energized = hasFocus()

        if (state.getValue(SplinterCasterBlock.ACTIVE) != active || state.getValue(BlockCircleComponent.ENERGIZED) != energized) {
            level.setBlock(
                worldPosition,
                state
                    .setValue(SplinterCasterBlock.ACTIVE, active)
                    .setValue(BlockCircleComponent.ENERGIZED, energized),
                3
            )
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        focus = if (tag.contains(TAG_FOCUS)) ItemStack.of(tag.getCompound(TAG_FOCUS)) else ItemStack.EMPTY
        waitingForSplinter = tag.getBoolean(TAG_WAITING)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!focus.isEmpty) {
            tag.put(TAG_FOCUS, focus.save(CompoundTag()))
        }
        if (waitingForSplinter) {
            tag.putBoolean(TAG_WAITING, true)
        }
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        if (!focus.isEmpty) {
            tag.put(TAG_FOCUS, focus.save(CompoundTag()))
        }
        if (waitingForSplinter) {
            tag.putBoolean(TAG_WAITING, true)
        }
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    private fun markUpdated() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    companion object {
        private const val TAG_FOCUS = "focus"
        private const val TAG_WAITING = "waiting"
    }
}
