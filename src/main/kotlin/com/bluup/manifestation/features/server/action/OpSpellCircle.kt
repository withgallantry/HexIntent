package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getIntBetween
import at.petrak.hexcasting.api.casting.getList
import at.petrak.hexcasting.api.casting.getVec3
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.misc.MediaConstants
import com.bluup.manifestation.server.ManifestationServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.nbt.CompoundTag

/**
 * Stack shape on entry (top -> bottom):
 *   optional angle vector (vector, projected onto circle plane)
 *   optional size tier (number 1..6, default 3)
 *   lifetime ticks (number)
 *   circle facing (vector)
 *   circle origin (vector)
 *   patterns (list of pattern iotas)
 */
object OpSpellCircle : VarargConstMediaAction {
    private const val MAX_PATTERNS = 48
    private const val MAX_TICKS = 1200
    private const val DEFAULT_SIZE_TIER = 3
    override val mediaCost: Long
        get() = CIRCLE_MEDIA_COST

    override fun argc(stack: List<Iota>): Int {
        return when {
            stack.getOrNull(0) is Vec3Iota && stack.getOrNull(1) is DoubleIota -> 6
            stack.getOrNull(0) is Vec3Iota -> 5
            stack.getOrNull(0) is DoubleIota && stack.getOrNull(1) is DoubleIota -> 5
            else -> 4
        }
    }

    override fun execute(
        args: List<Iota>,
        argc: Int,
        userData: CompoundTag,
        env: CastingEnvironment
    ): List<Iota> {
        var sizeTier = DEFAULT_SIZE_TIER
        var angleVector: net.minecraft.world.phys.Vec3? = null

        when (argc) {
            6 -> {
                sizeTier = listOf(args[4]).getIntBetween(0, 1, 6, 1)
                val angle = args[5] as? Vec3Iota
                    ?: throw MishapInvalidIota.ofType(args[5], 0, "vector")
                if (angle.vec3.lengthSqr() <= 1.0e-8) {
                    throw MishapInvalidIota.ofType(args[5], 0, "non-zero vector")
                }
                angleVector = angle.vec3
            }

            5 -> {
                when (val optional = args[4]) {
                    is DoubleIota -> {
                        sizeTier = listOf(optional).getIntBetween(0, 1, 6, 1)
                    }

                    is Vec3Iota -> {
                        if (optional.vec3.lengthSqr() <= 1.0e-8) {
                            throw MishapInvalidIota.ofType(optional, 0, "non-zero vector")
                        }
                        angleVector = optional.vec3
                    }

                    else -> throw MishapInvalidIota.ofType(optional, 0, "number between 1 and 6 or vector")
                }
            }
        }

        val lifetimeTicks = args.getIntBetween(3, 1, MAX_TICKS, argc)

        val facing = args.getVec3(2, argc)
        if (facing.lengthSqr() <= 1.0e-8) {
            throw MishapInvalidIota.ofType(args[2], 1, "non-zero vector")
        }
        val normal = facing.normalize()

        val requestedAngle = angleVector ?: defaultAngleVector(normal)
        val projectedAngle = requestedAngle.subtract(normal.scale(requestedAngle.dot(normal)))
        if (projectedAngle.lengthSqr() <= 1.0e-8) {
            throw MishapInvalidIota.ofType(args[2], 1, "facing not parallel to angle vector")
        }
        val openingAngle = projectedAngle.normalize()

        val origin = args.getVec3(1, argc)
        env.assertVecInRange(origin)

        val patternEntries = args.getList(0, argc).toList()
        if (patternEntries.isEmpty()) {
            throw MishapInvalidIota.ofType(args[0], 3, "non-empty list")
        }
        if (patternEntries.size > MAX_PATTERNS) {
            throw MishapInvalidIota.ofType(args[0], 3, "list with at most $MAX_PATTERNS patterns")
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

        return listOf()
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
