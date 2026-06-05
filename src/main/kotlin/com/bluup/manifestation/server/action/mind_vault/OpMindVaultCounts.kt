package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.block.MindVaultBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Stack shape on entry (top -> bottom):
 *   mind vault position vector
 */
object OpMindVaultCounts : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val targetIota = args[0]

        val targetPos = (targetIota as? Vec3Iota)?.let {
            env.assertVecInRange(it.vec3)
            BlockPos.containing(it.vec3)
        } ?: throw MishapInvalidIota.ofType(targetIota, 0, "vector")

        val state = env.world.getBlockState(targetPos)
        if (state.block != ManifestationBlocks.MIND_VAULT_BLOCK) {
            throw MishapBadBlock(targetPos, Component.translatable("block.manifestation.mind_vault"))
        }

        val mindVault = env.world.getBlockEntity(targetPos) as? MindVaultBlockEntity
            ?: throw MishapBadBlock(targetPos, Component.translatable("block.manifestation.mind_vault"))

        val now = env.world.gameTime
        var totalStored = 0
        var available = 0
        for (slot in 0 until MindVaultBlockEntity.SLOT_COUNT) {
            if (!mindVault.isSlotOccupied(slot)) {
                continue
            }

            totalStored += 1
            if (mindVault.getSlotCooldownRemainingTicks(slot, now) <= 0L) {
                available += 1
            }
        }

        return listOf(
            ListIota(
                listOf(
                    DoubleIota(available.toDouble()),
                    DoubleIota(totalStored.toDouble())
                )
            )
        )
    }
}