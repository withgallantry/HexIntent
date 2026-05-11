package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.minecraft.server.level.ServerPlayer

/**
 * Stack shape on entry (top -> bottom):
 *   payload iota list
 *   delay ticks
 *   summon position
 */
object OpManifestSplinter : Action {
    override fun operate(
        env: at.petrak.hexcasting.api.casting.eval.CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 3) {
            throw MishapNotEnoughArgs(3, stack.size)
        }

        val payloadIota = stack.removeAt(stack.lastIndex)
        val delayIota = stack.removeAt(stack.lastIndex)
        val posIota = stack.removeAt(stack.lastIndex)

        val payload = if (payloadIota is ListIota) {
            val list = payloadIota.list.toList()
            if (list.isEmpty()) {
                stack.add(posIota)
                stack.add(delayIota)
                stack.add(payloadIota)
                throw MishapInvalidIota.ofType(payloadIota, 0, "non-empty list of iotas")
            }
            list
        } else {
            stack.add(posIota)
            stack.add(delayIota)
            stack.add(payloadIota)
            throw MishapInvalidIota.ofType(payloadIota, 0, "list of iotas")
        }

        val delayTicks = if (delayIota is DoubleIota) {
            val rounded = Math.round(delayIota.double)
            if (rounded < 0L) {
                stack.add(posIota)
                stack.add(delayIota)
                stack.add(payloadIota)
                throw MishapInvalidIota.ofType(delayIota, 1, "non-negative number")
            }
            rounded
        } else {
            stack.add(posIota)
            stack.add(delayIota)
            stack.add(payloadIota)
            throw MishapInvalidIota.ofType(delayIota, 1, "number")
        }

        val summonPos = if (posIota is Vec3Iota) {
            env.assertVecInRange(posIota.vec3)
            posIota.vec3
        } else {
            stack.add(posIota)
            stack.add(delayIota)
            stack.add(payloadIota)
            throw MishapInvalidIota.ofType(posIota, 2, "vector")
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val summonResult = try {
            SplinterRuntime.prepareSummon(env, caster, summonPos, delayTicks, payload, image)
        } catch (e: IllegalArgumentException) {
            if (e.message == "payload_too_large") {
                throw MishapInvalidIota.ofType(payloadIota, 0, "list up to ${SplinterRuntime.MAX_PAYLOAD_IOTAS} iotas")
            }
            throw e
        } catch (e: IllegalStateException) {
            if (e.message == "too_many_splinters") {
                val configured = ManifestationConfig.splinterMaxActivePerOwner()
                val expected = if (configured < 0) "server-configured active splinter limit" else "fewer than $configured active splinters"
                throw MishapInvalidIota.ofType(posIota, 2, expected)
            }
            if (e.message == "anchored_relocation") {
                throw MishapInvalidIota.ofType(posIota, 2, "vector equal to splinter anchor")
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

        return OperationResult(
            image2,
            sideEffects,
            continuation,
            HexEvalSounds.NORMAL_EXECUTE
        )
    }
}
