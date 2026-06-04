package com.bluup.manifestation.server.block

import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import com.bluup.manifestation.server.KotlinNbtCompat
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

class HexReliquaryBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.HEX_RELIQUARY_BLOCK_ENTITY, pos, state) {

    private val labels = MutableList(HexReliquaryBlock.SLOT_COUNT) { defaultLabel(it) }
    private val iotaTags = MutableList<CompoundTag?>(HexReliquaryBlock.SLOT_COUNT) { null }

    fun getSlotLabel(slot: Int): String {
        val safeSlot = slot.coerceIn(0, HexReliquaryBlock.SLOT_COUNT - 1)
        val label = labels[safeSlot]
        return if (label.isBlank()) defaultLabel(safeSlot) else label
    }

    fun setSlot(slot: Int, iota: Iota, label: String) {
        val safeSlot = slot.coerceIn(0, HexReliquaryBlock.SLOT_COUNT - 1)
        iotaTags[safeSlot] = IotaType.serialize(iota)
        labels[safeSlot] = if (label.isBlank()) defaultLabel(safeSlot) else label
        markUpdated()
    }

    fun hasSlotValue(slot: Int): Boolean {
        val safeSlot = slot.coerceIn(0, HexReliquaryBlock.SLOT_COUNT - 1)
        return iotaTags[safeSlot] != null
    }

    fun getSlotIota(slot: Int, level: ServerLevel): Iota? {
        val safeSlot = slot.coerceIn(0, HexReliquaryBlock.SLOT_COUNT - 1)
        val tag = iotaTags[safeSlot] ?: return null
        return try {
            IotaType.deserialize(tag.copy(), level)
        } catch (_: Throwable) {
            null
        }
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)

        for (i in 0 until HexReliquaryBlock.SLOT_COUNT) {
            labels[i] = defaultLabel(i)
            iotaTags[i] = null
        }

        if (!KotlinNbtCompat.contains(tag, TAG_SLOTS)) {
            return
        }

        val slots = KotlinNbtCompat.getList(tag, TAG_SLOTS, CompoundTag.TAG_COMPOUND.toInt())
        for (entryTag in slots) {
            if (entryTag !is CompoundTag) {
                continue
            }

            val slot = KotlinNbtCompat.getInt(entryTag, TAG_SLOT_INDEX)
            if (slot !in 0 until HexReliquaryBlock.SLOT_COUNT) {
                continue
            }

            val label = KotlinNbtCompat.getString(entryTag, TAG_LABEL)
            labels[slot] = if (label.isBlank()) defaultLabel(slot) else label

            if (KotlinNbtCompat.contains(entryTag, TAG_IOTA, CompoundTag.TAG_COMPOUND.toInt())) {
                iotaTags[slot] = KotlinNbtCompat.getCompound(entryTag, TAG_IOTA)
            }
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        KotlinNbtCompat.put(tag, TAG_SLOTS, buildSlotsTag())
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        KotlinNbtCompat.put(tag, TAG_SLOTS, buildSlotsTag())
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    private fun buildSlotsTag(): ListTag {
        val slots = ListTag()
        for (slot in 0 until HexReliquaryBlock.SLOT_COUNT) {
            val entry = CompoundTag()
            KotlinNbtCompat.putInt(entry, TAG_SLOT_INDEX, slot)
            KotlinNbtCompat.putString(entry, TAG_LABEL, labels[slot])

            val serialized = iotaTags[slot]
            if (serialized != null) {
                KotlinNbtCompat.put(entry, TAG_IOTA, serialized)
            }

            slots.add(entry)
        }
        return slots
    }

    private fun markUpdated() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    companion object {
        private const val TAG_SLOTS = "slots"
        private const val TAG_SLOT_INDEX = "slot"
        private const val TAG_LABEL = "label"
        private const val TAG_IOTA = "iota"

        private fun defaultLabel(slot: Int): String {
            return "Slot ${slot + 1}"
        }
    }
}
