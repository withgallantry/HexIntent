package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationServer
import net.minecraft.server.level.ServerLevel

/**
 * Stack shape on entry (top -> bottom):
 *   lifetime ticks (number)
 *   circle facing (vector)
 *   circle origin (vector)
 *   patterns (list of pattern iotas)
 */
object OpSpellCircle : Action {
    private const val MAX_PATTERNS = 48
    private const val MAX_TICKS = 1200

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 4) {
            throw MishapNotEnoughArgs(4, stack.size)
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

        val level = env.world as? ServerLevel
        if (level != null) {
            ManifestationServer.sendSpellCircleTo(level, origin, facing.normalize(), lifetimeTicks, patterns)
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
