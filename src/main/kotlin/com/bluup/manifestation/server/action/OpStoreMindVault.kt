package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
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
object OpStoreMindVault : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val villagerIota = stack.removeAt(stack.lastIndex)
        val targetIota = stack.removeAt(stack.lastIndex)

        val villager = (villagerIota as? EntityIota)?.entity as? Villager
            ?: run {
                stack.add(targetIota)
                stack.add(villagerIota)
                throw MishapInvalidIota.ofType(villagerIota, 0, "villager entity")
            }
        env.assertEntityInRange(villager)

        val targetPos = (targetIota as? Vec3Iota)?.let {
            env.assertVecInRange(it.vec3)
            BlockPos.containing(it.vec3)
        } ?: run {
            stack.add(targetIota)
            stack.add(villagerIota)
            throw MishapInvalidIota.ofType(targetIota, 1, "vector")
        }

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

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
