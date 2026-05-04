package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresSplinterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv

object OpGetSplinterLocation : Action {
    override fun operate(
        env: at.petrak.hexcasting.api.casting.eval.CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val splinterEnv = env as? SplinterCastEnv ?: throw MishapRequiresSplinterWill()

        val stack = image.stack.toMutableList()
        stack.add(Vec3Iota(splinterEnv.splinterOrigin))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}