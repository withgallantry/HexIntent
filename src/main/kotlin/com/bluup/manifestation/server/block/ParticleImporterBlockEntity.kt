package com.bluup.manifestation.server.block

import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.server.action.ParticleBlobCodec
import com.bluup.manifestation.server.iota.ParticleBlobIota
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class ParticleImporterBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.PARTICLE_IMPORTER_BLOCK_ENTITY, pos, state) {

    private var focus: ItemStack = ItemStack.EMPTY

    fun hasFocus(): Boolean = !focus.isEmpty

    fun getFocusCopy(): ItemStack = if (focus.isEmpty) ItemStack.EMPTY else focus.copy()

    fun setFocus(stack: ItemStack) {
        val normalized = if (stack.isEmpty) ItemStack.EMPTY else stack.copyWithCount(1)
        if (ItemStack.isSameItemSameTags(focus, normalized)) {
            return
        }
        focus = normalized
        markUpdated()
    }

    fun popFocus(): ItemStack {
        val out = focus
        focus = ItemStack.EMPTY
        markUpdated()
        return out
    }

    fun canAcceptFocus(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }
        return IXplatAbstractions.INSTANCE.findDataHolder(stack) != null
    }

    fun writeBlob(blob: ByteArray, pointCount: Int): String? {
        if (focus.isEmpty) {
            return "No focus inserted."
        }

        val holder = IXplatAbstractions.INSTANCE.findDataHolder(focus)
            ?: return "Inserted item is not a writable focus."

        val weight = ParticleBlobCodec.computeVirtualWeight(pointCount, blob.size)
        val iota = ParticleBlobIota(blob, pointCount, weight)
        if (!holder.writeIota(iota, true)) {
            return "Focus cannot store particle blob data."
        }

        holder.writeIota(iota, false)
        markUpdated()
        return null
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        focus = if (tag.contains(TAG_FOCUS, CompoundTag.TAG_COMPOUND.toInt())) {
            ItemStack.of(tag.getCompound(TAG_FOCUS))
        } else {
            ItemStack.EMPTY
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!focus.isEmpty) {
            tag.put(TAG_FOCUS, focus.save(CompoundTag()))
        }
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        if (!focus.isEmpty) {
            tag.put(TAG_FOCUS, focus.save(CompoundTag()))
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
    }
}