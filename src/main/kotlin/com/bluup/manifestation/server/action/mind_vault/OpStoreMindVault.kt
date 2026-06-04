package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.block.MindVaultBlockEntity
import com.bluup.manifestation.server.mishap.MishapMindVaultFull
import com.bluup.manifestation.server.mishap.MishapMindVaultTypeMismatch
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.npc.Villager

/**
 * Stack shape on entry (top -> bottom):
 *   villager entity
 *   mind vault position vector
 */
object OpStoreMindVault : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val targetIota = args[0]
        val villagerIota = args[1]

        val villager = (villagerIota as? EntityIota)?.entity as? Villager
            ?: throw MishapInvalidIota.ofType(villagerIota, 0, "villager entity")
        env.assertEntityInRange(villager)

        val targetPos = (targetIota as? Vec3Iota)?.let {
            env.assertVecInRange(it.vec3)
            BlockPos.containing(it.vec3)
        } ?: throw MishapInvalidIota.ofType(targetIota, 1, "vector")

        val state = env.world.getBlockState(targetPos)
        if (state.block != ManifestationBlocks.MIND_VAULT_BLOCK) {
            throw MishapBadBlock(targetPos, Component.translatable("block.manifestation.mind_vault"))
        }

        val mindVault = env.world.getBlockEntity(targetPos) as? MindVaultBlockEntity
            ?: throw MishapBadBlock(targetPos, Component.translatable("block.manifestation.mind_vault"))

        when (mindVault.tryStore(villager)) {
            MindVaultBlockEntity.StoreResult.STORED -> {
                villager.discard()
                env.world.playSound(
                    null,
                    targetPos,
                    SoundEvents.AMETHYST_CLUSTER_PLACE,
                    SoundSource.BLOCKS,
                    0.9f,
                    1.1f
                )
            }

            MindVaultBlockEntity.StoreResult.FULL -> throw MishapMindVaultFull()
            MindVaultBlockEntity.StoreResult.TYPE_MISMATCH -> throw MishapMindVaultTypeMismatch()
            MindVaultBlockEntity.StoreResult.INVALID_PROFILE -> {
                throw MishapInvalidIota.ofType(villagerIota, 0, "villager with profession and level")
            }
        }

        return listOf()
    }
}
