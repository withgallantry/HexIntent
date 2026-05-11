package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresSplinterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.minecraft.server.level.ServerPlayer

/**
 * Stack shape on entry (top -> bottom):
 *   delay ticks
 *   summon position
 */
object OpRenewSplinter : Action {
    override fun operate(
        env: at.petrak.hexcasting.api.casting.eval.CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val splinterEnv = env as? SplinterCastEnv ?: throw MishapRequiresSplinterWill()
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val delayIota = stack.removeAt(stack.lastIndex)
        val posIota = stack.removeAt(stack.lastIndex)

        val delayTicks = if (delayIota is DoubleIota) {
            val rounded = Math.round(delayIota.double)
            if (rounded < 0L) {
                stack.add(posIota)
                stack.add(delayIota)
                throw MishapInvalidIota.ofType(delayIota, 0, "non-negative number")
            }
            rounded
        } else {
            stack.add(posIota)
            stack.add(delayIota)
            throw MishapInvalidIota.ofType(delayIota, 0, "number")
        }

        val summonPos = if (posIota is Vec3Iota) {
            env.assertVecInRange(posIota.vec3)
            posIota.vec3
        } else {
            stack.add(posIota)
            stack.add(delayIota)
            throw MishapInvalidIota.ofType(posIota, 1, "vector")
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresSplinterWill()
        val summonResult = try {
            SplinterRuntime.prepareRenew(splinterEnv, caster, summonPos, delayTicks, image)
        } catch (e: IllegalStateException) {
            if (e.message == "anchored_relocation") {
                throw MishapInvalidIota.ofType(posIota, 1, "vector equal to splinter anchor")
            }
            throw e
        }

        if (summonResult.mediaCost > 0L && env.extractMedia(summonResult.mediaCost, true) > 0) {
            throw MishapNotEnoughMedia(summonResult.mediaCost)
        }

        SplinterRuntime.commitSummon(caster.server, summonResult)

        val image2 = image.withUsedOp().copy(stack = stack)
        val sideEffects = if (summonResult.mediaCost > 0L) {
            listOf(OperatorSideEffect.ConsumeMedia(summonResult.mediaCost))
        } else {
            listOf()
        }

        return OperationResult(image2, sideEffects, continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}