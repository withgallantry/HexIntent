package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationServer
import net.minecraft.server.level.ServerLevel

/**
 * Stack shape on entry (top -> bottom):
 *   optional angle vector (vector, projected onto circle plane)
 *   optional size tier (number 1..6, default 3)
 *   lifetime ticks (number)
 *   circle facing (vector)
 *   circle origin (vector)
 *   patterns (list of pattern iotas)
 */
object OpSpellCircle : Action {
    private const val MAX_PATTERNS = 48
    private const val MAX_TICKS = 1200
    private const val DEFAULT_SIZE_TIER = 3

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 4) {
            throw MishapNotEnoughArgs(4, stack.size)
        }

        var sizeTier = DEFAULT_SIZE_TIER
        var angleVector: net.minecraft.world.phys.Vec3? = null
        var consumedTier = false

        while (stack.size > 4) {
            val optional = stack.lastOrNull() ?: break
            when {
                optional is DoubleIota && !consumedTier -> {
                    val parsedTier = Math.round(optional.double).toInt()
                    if (parsedTier < 1 || parsedTier > 6) {
                        throw MishapInvalidIota.ofType(optional, 0, "number between 1 and 6")
                    }
                    sizeTier = parsedTier
                    consumedTier = true
                    stack.removeAt(stack.lastIndex)
                }

                optional is Vec3Iota && angleVector == null -> {
                    if (optional.vec3.lengthSqr() <= 1.0e-8) {
                        throw MishapInvalidIota.ofType(optional, 0, "non-zero vector")
                    }
                    angleVector = optional.vec3
                    stack.removeAt(stack.lastIndex)
                }

                else -> {
                    throw MishapInvalidIota.ofType(optional, 0, "number between 1 and 6 or vector")
                }
            }
        }

        val ticksIota = stack.removeAt(stack.lastIndex)
        val facingIota = stack.removeAt(stack.lastIndex)
        val originIota = stack.removeAt(stack.lastIndex)
        val patternsIota = stack.removeAt(stack.lastIndex)

        val lifetimeTicks = (ticksIota as? DoubleIota)?.let { Math.round(it.double).toInt() }
            ?: throw MishapInvalidIota.ofType(ticksIota, 0, "number")
        if (lifetimeTicks < 1 || lifetimeTicks > MAX_TICKS) {
            throw MishapInvalidIota.ofType(ticksIota, 0, "number between 1 and $MAX_TICKS")
        }

        val facing = (facingIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(facingIota, 1, "vector")
        if (facing.lengthSqr() <= 1.0e-8) {
            throw MishapInvalidIota.ofType(facingIota, 1, "non-zero vector")
        }
        val normal = facing.normalize()

        val requestedAngle = angleVector ?: defaultAngleVector(normal)
        val projectedAngle = requestedAngle.subtract(normal.scale(requestedAngle.dot(normal)))
        if (projectedAngle.lengthSqr() <= 1.0e-8) {
            throw MishapInvalidIota.ofType(facingIota, 1, "facing not parallel to angle vector")
        }
        val openingAngle = projectedAngle.normalize()

        val origin = (originIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(originIota, 2, "vector")
        env.assertVecInRange(origin)

        val patternEntries = (patternsIota as? ListIota)?.list?.toList()
            ?: throw MishapInvalidIota.ofType(patternsIota, 3, "list")
        if (patternEntries.isEmpty()) {
            throw MishapInvalidIota.ofType(patternsIota, 3, "non-empty list")
        }
        if (patternEntries.size > MAX_PATTERNS) {
            throw MishapInvalidIota.ofType(patternsIota, 3, "list with at most $MAX_PATTERNS patterns")
        }

        val patterns = ArrayList<Pair<String, at.petrak.hexcasting.api.casting.math.HexDir>>(patternEntries.size)
        for (entry in patternEntries) {
            val patternIota = entry as? PatternIota
                ?: throw MishapInvalidIota.ofType(entry, 3, "list of patterns")
            val pattern = patternIota.pattern
            patterns.add(pattern.anglesSignature() to pattern.startDir)
        }

        val level: ServerLevel = env.world
        ManifestationServer.sendSpellCircleTo(level, origin, normal, openingAngle, lifetimeTicks, sizeTier, patterns, env.pigment)

        if (env.extractMedia(CIRCLE_MEDIA_COST, true) > 0) {
            throw MishapNotEnoughMedia(CIRCLE_MEDIA_COST)
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(
            image2,
            listOf(OperatorSideEffect.ConsumeMedia(CIRCLE_MEDIA_COST)),
            continuation,
            HexEvalSounds.NORMAL_EXECUTE
        )
    }

    private val CIRCLE_MEDIA_COST = (MediaConstants.DUST_UNIT / 500L).coerceAtLeast(1L)

    private fun defaultAngleVector(normal: net.minecraft.world.phys.Vec3): net.minecraft.world.phys.Vec3 {
        val up = net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0)
        return if (kotlin.math.abs(normal.dot(up)) > 0.92) {
            net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0)
        } else {
            up
        }
    }
}
