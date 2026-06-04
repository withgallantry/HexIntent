package com.bluup.manifestation.server.block

import com.bluup.manifestation.server.KotlinNbtCompat
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.entity.npc.VillagerData
import net.minecraft.world.entity.npc.VillagerProfession
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

class MindVaultBlockEntity(
    pos: BlockPos,
    state: BlockState
) : BlockEntity(ManifestationBlocks.MIND_VAULT_BLOCK_ENTITY, pos, state) {

    enum class StoreResult {
        STORED,
        FULL,
        TYPE_MISMATCH,
        INVALID_PROFILE
    }

    private var lockedProfessionId: String? = null
    private var lockedVillagerLevel: Int = 0
    private val occupiedSlots = BooleanArray(SLOT_COUNT)
    private val cooldownUntilGameTime = LongArray(SLOT_COUNT)

    fun occupiedSlotCount(): Int = occupiedSlots.count { it }

    fun isSlotOccupied(slot: Int): Boolean {
        if (slot !in 0 until SLOT_COUNT) return false
        return occupiedSlots[slot]
    }

    fun getSlotCooldownRemainingTicks(slot: Int, gameTime: Long): Long {
        if (slot !in 0 until SLOT_COUNT || !occupiedSlots[slot]) return 0L
        return (cooldownUntilGameTime[slot] - gameTime).coerceAtLeast(0L)
    }

    fun lockedProfessionIdString(): String? = lockedProfessionId

    fun lockedVillagerLevel(): Int = lockedVillagerLevel

    fun tryStore(villager: Villager): StoreResult {
        val data = villager.villagerData
        val professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.profession).toString()
        val level = data.level
        if (level !in 1..5) {
            return StoreResult.INVALID_PROFILE
        }

        if (lockedProfessionId == null) {
            lockedProfessionId = professionId
            lockedVillagerLevel = level
        } else if (lockedProfessionId != professionId || lockedVillagerLevel != level) {
            return StoreResult.TYPE_MISMATCH
        }

        val free = occupiedSlots.indexOfFirst { !it }
        if (free == -1) {
            return StoreResult.FULL
        }

        occupiedSlots[free] = true
        cooldownUntilGameTime[free] = 0L
        markUpdated()
        return StoreResult.STORED
    }

    fun claimForFlay(gameTime: Long): Boolean {
        for (slot in 0 until SLOT_COUNT) {
            if (!occupiedSlots[slot]) continue
            if (cooldownUntilGameTime[slot] > gameTime) continue

            cooldownUntilGameTime[slot] = gameTime + FLAY_COOLDOWN_TICKS
            markUpdated()
            return true
        }
        return false
    }

    fun displayedIconStack(): ItemStack {
        val profession = resolveLockedProfession() ?: return ItemStack.EMPTY
        val icon = PROFESSION_ICONS[profession] ?: ItemStack.EMPTY
        return if (icon.isEmpty) ItemStack.EMPTY else icon.copy()
    }

    fun createTemplateVillager(level: ServerLevel, sourcePos: BlockPos): Villager? {
        val profession = resolveLockedProfession() ?: return null
        if (lockedVillagerLevel !in 1..5) return null

        val villager = EntityType.VILLAGER.create(level) ?: return null
        val type = villager.villagerData.type
        villager.villagerData = VillagerData(type, profession, lockedVillagerLevel)

        val center = Vec3.atCenterOf(sourcePos)
        villager.moveTo(center.x, center.y, center.z, 0f, 0f)
        return villager
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)

        lockedProfessionId = if (KotlinNbtCompat.contains(tag, TAG_LOCKED_PROFESSION, CompoundTag.TAG_STRING.toInt())) {
            KotlinNbtCompat.getString(tag, TAG_LOCKED_PROFESSION)
        } else {
            null
        }
        lockedVillagerLevel = KotlinNbtCompat.getInt(tag, TAG_LOCKED_LEVEL).coerceIn(0, 5)

        occupiedSlots.fill(false)
        cooldownUntilGameTime.fill(0L)

        val slots = KotlinNbtCompat.getList(tag, TAG_SLOTS, CompoundTag.TAG_COMPOUND.toInt())
        for (entryRaw in slots) {
            val entry = entryRaw as? CompoundTag ?: continue
            val slot = KotlinNbtCompat.getInt(entry, TAG_SLOT_INDEX)
            if (slot !in 0 until SLOT_COUNT) continue

            occupiedSlots[slot] = KotlinNbtCompat.getBoolean(entry, TAG_SLOT_OCCUPIED)
            cooldownUntilGameTime[slot] = KotlinNbtCompat.getLong(entry, TAG_SLOT_COOLDOWN)
        }
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        val professionId = lockedProfessionId
        if (!professionId.isNullOrBlank()) {
            KotlinNbtCompat.putString(tag, TAG_LOCKED_PROFESSION, professionId)
        }
        KotlinNbtCompat.putInt(tag, TAG_LOCKED_LEVEL, lockedVillagerLevel)
        KotlinNbtCompat.put(tag, TAG_SLOTS, buildSlotsTag())
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        val professionId = lockedProfessionId
        if (!professionId.isNullOrBlank()) {
            KotlinNbtCompat.putString(tag, TAG_LOCKED_PROFESSION, professionId)
        }
        KotlinNbtCompat.putInt(tag, TAG_LOCKED_LEVEL, lockedVillagerLevel)
        KotlinNbtCompat.put(tag, TAG_SLOTS, buildSlotsTag())
        return tag
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    private fun buildSlotsTag(): ListTag {
        val out = ListTag()
        for (slot in 0 until SLOT_COUNT) {
            val entry = CompoundTag()
            KotlinNbtCompat.putInt(entry, TAG_SLOT_INDEX, slot)
            KotlinNbtCompat.putBoolean(entry, TAG_SLOT_OCCUPIED, occupiedSlots[slot])
            KotlinNbtCompat.putLong(entry, TAG_SLOT_COOLDOWN, cooldownUntilGameTime[slot])
            out.add(entry)
        }
        return out
    }

    private fun resolveLockedProfession(): VillagerProfession? {
        val id = lockedProfessionId ?: return null
        val loc = ResourceLocation.tryParse(id) ?: return null
        return BuiltInRegistries.VILLAGER_PROFESSION.get(loc)
    }

    private fun markUpdated() {
        setChanged()
        level?.sendBlockUpdated(worldPosition, blockState, blockState, 3)
    }

    companion object {
        const val SLOT_COUNT = 6
        const val FLAY_COOLDOWN_TICKS = 20L * 300L

        private const val TAG_LOCKED_PROFESSION = "locked_profession"
        private const val TAG_LOCKED_LEVEL = "locked_level"
        private const val TAG_SLOTS = "slots"
        private const val TAG_SLOT_INDEX = "slot"
        private const val TAG_SLOT_OCCUPIED = "occupied"
        private const val TAG_SLOT_COOLDOWN = "cooldown_until"

        private val PROFESSION_ICONS: Map<VillagerProfession, ItemStack> = mapOf(
            VillagerProfession.ARMORER to ItemStack(Items.BLAST_FURNACE),
            VillagerProfession.BUTCHER to ItemStack(Items.SMOKER),
            VillagerProfession.CARTOGRAPHER to ItemStack(Items.CARTOGRAPHY_TABLE),
            VillagerProfession.CLERIC to ItemStack(Items.BREWING_STAND),
            VillagerProfession.FARMER to ItemStack(Items.COMPOSTER),
            VillagerProfession.FISHERMAN to ItemStack(Items.BARREL),
            VillagerProfession.FLETCHER to ItemStack(Items.FLETCHING_TABLE),
            VillagerProfession.LEATHERWORKER to ItemStack(Items.CAULDRON),
            VillagerProfession.LIBRARIAN to ItemStack(Items.LECTERN),
            VillagerProfession.MASON to ItemStack(Items.STONECUTTER),
            VillagerProfession.SHEPHERD to ItemStack(Items.LOOM),
            VillagerProfession.TOOLSMITH to ItemStack(Items.SMITHING_TABLE),
            VillagerProfession.WEAPONSMITH to ItemStack(Items.GRINDSTONE)
        )
    }
}
