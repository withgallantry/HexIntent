package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.BlockHitResult

/**
 * Let the caster interact with a block, exit if successful.
 *
 */
object OpExitIfInteracting : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val blockHit = aimedBlockHit(caster)
        val shouldExit = blockHit != null &&
            tryYieldToBlockInteraction(caster, env.castingHand, blockHit)

        val image2 = image.withUsedOp()
        val newContinuation = if (shouldExit) SpellContinuation.Done else continuation
        return OperationResult(image2, listOf(), newContinuation, HexEvalSounds.MUTE)
    }

    private fun aimedBlockHit(player: ServerPlayer): BlockHitResult? {
        val hit = player.pick(5.0, 0.0f, false)
        return hit as? BlockHitResult
    }

    private fun tryYieldToBlockInteraction(
        player: ServerPlayer,
        hand: net.minecraft.world.InteractionHand,
        blockHit: BlockHitResult
    ): Boolean {
        val level = player.serverLevel()
        val pos = blockHit.blockPos
        val state = level.getBlockState(pos)

        if (state.isAir) {
            return false
        }

        val result = state.use(level, player, hand, blockHit)
        return result.consumesAction()
    }
}
