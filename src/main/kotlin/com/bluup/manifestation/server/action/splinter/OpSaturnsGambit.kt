package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.minecraft.server.level.ServerPlayer

/**
 * Stack shape on entry (top -> bottom):
 *   pattern list
 */
object OpSaturnsGambit : Action {
    private const val SATURN_AMBIT_RADIUS = 32.0

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.isEmpty()) {
            throw MishapNotEnoughArgs(1, 0)
        }

        val payloadIota = stack.removeAt(stack.lastIndex)
        val patternEntries = try {
            listOf(payloadIota).getList(0, 1).toList()
        } catch (_: MishapInvalidIota) {
            throw MishapInvalidIota.ofType(payloadIota, 0, "list of patterns")
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val summonResult = try {
            SplinterRuntime.prepareSummon(
            env,
                caster,
                caster.position(),
                0L,
                patternEntries,
                image
            )
        } catch (e: IllegalStateException) {
            // Even though we try to hide it's a splinter under the hood, we can't have it silently failing so better to throw a mishap.
            // TODO: Look at changing this in the future.
            if (e.message == "too_many_splinters") {
                val configured = ManifestationConfig.splinterMaxActivePerOwner()
                val expected = if (configured < 0) {
                    "server-configured active splinter limit"
                } else {
                    "fewer than $configured active splinters"
                }
                throw MishapInvalidIota.ofType(payloadIota, 0, expected)
            }
            throw e
        }
        summonResult.record.ambitRadius = SATURN_AMBIT_RADIUS.coerceAtLeast(SplinterCastEnv.SPLINTER_AMBIT_RADIUS)
        summonResult.record.allowRenew = false

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