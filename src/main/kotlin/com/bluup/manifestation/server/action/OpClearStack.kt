package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerPlayer

/**
 * Clears the current casting stack image.
 *
 * This is player-only and cannot be invoked by splinter environments.
 */
object OpClearStack : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        if (caster is FakePlayer || env is SplinterCastEnv) {
            throw MishapRequiresCasterWill()
        }

        val image2 = image.withUsedOp().copy(stack = mutableListOf())
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
