package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * Stack shape on entry (top -> bottom):
 *   particle type? (optional number: 0..14)
 *   id (number)
 *   transition ticks (number)
 *   color to (vec)
 *   color from (vec)
 *   position (vec)
 */
object OpHexTrail : Action {

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()

        var particleType = 0
        if (stack.size >= 6 && stack.last() is DoubleIota) {
            val typeIota = stack.removeAt(stack.lastIndex) as DoubleIota
            particleType = Math.round(typeIota.double).toInt()
        }

        if (stack.size < 5) {
            throw MishapNotEnoughArgs(5, stack.size)
        }

        val idIota = stack.removeAt(stack.lastIndex)
        val ticksIota = stack.removeAt(stack.lastIndex)
        val colorToIota = stack.removeAt(stack.lastIndex)
        val colorFromIota = stack.removeAt(stack.lastIndex)
        val posIota = stack.removeAt(stack.lastIndex)

        val trailId = (idIota as? DoubleIota)?.let { Math.round(it.double) }
            ?: throw MishapInvalidIota.ofType(idIota, 0, "number")

        val transitionTicks = (ticksIota as? DoubleIota)?.let { Math.round(it.double).toInt() }
            ?: throw MishapInvalidIota.ofType(ticksIota, 1, "number")
        if (transitionTicks < 1) {
            throw MishapInvalidIota.ofType(ticksIota, 1, "positive number")
        }

        val colorFrom = (colorFromIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(colorFromIota, 2, "vector")
        val colorTo = (colorToIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(colorToIota, 3, "vector")

        val position = (posIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(posIota, 4, "vector")
        env.assertVecInRange(position)

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        ManifestationServer.sendHexTrailTo(
            caster,
            position,
            clampColor(colorFrom),
            clampColor(colorTo),
            transitionTicks,
            trailId,
            particleType
        )

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun clampColor(color: Vec3): Vec3 {
        fun c(v: Double): Double = v.coerceIn(0.0, 1.0)
        return Vec3(c(color.x), c(color.y), c(color.z))
    }
}
