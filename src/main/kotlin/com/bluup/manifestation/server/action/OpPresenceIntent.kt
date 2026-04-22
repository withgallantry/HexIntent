package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.iota.PresenceIntentIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerLevel

/**
 * Constructor operator for presence intents.
 *
 * Stack shape on entry (top -> bottom):
 *   facing vector
 *   position vector
 */
object OpPresenceIntent : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val facingIota = stack.removeAt(stack.lastIndex)
        val positionIota = stack.removeAt(stack.lastIndex)

        val facing = if (facingIota is Vec3Iota) {
            val vec = facingIota.vec3
            if (vec.lengthSqr() <= 1.0e-10) {
                throw MishapInvalidIota.ofType(facingIota, 0, "non-zero vector")
            }
            vec
        } else {
            stack.add(positionIota)
            stack.add(facingIota)
            throw MishapInvalidIota.ofType(facingIota, 0, "vector")
        }

        val position = if (positionIota is Vec3Iota) {
            positionIota.vec3
        } else {
            stack.add(positionIota)
            stack.add(facingIota)
            throw MishapInvalidIota.ofType(positionIota, 1, "vector")
        }

        val level = env.castingEntity?.level() as? ServerLevel
            ?: throw MishapRequiresCasterWill()

        stack.add(PresenceIntentIota(position, facing, level.dimension().location().toString()))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}