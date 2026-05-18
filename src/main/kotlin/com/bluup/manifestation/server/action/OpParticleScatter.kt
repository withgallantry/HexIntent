package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f

/**
 * Stack shape on entry (top -> bottom):
 *   particles per point (number, 1..4)
 *   points (list)
 *
 * points list supports two entry shapes:
 *   * vec                -> white particle at vec
 *   * [vec, color vec]   -> colored particle at vec
 */
object OpParticleScatter : Action {
    private const val MAX_POINTS_PER_CAST = 256
    private const val MAX_TOTAL_PARTICLES = 1024

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val countIota = stack.removeAt(stack.lastIndex)
        val pointsIota = stack.removeAt(stack.lastIndex)

        val perPointCount = (countIota as? DoubleIota)?.let { Math.round(it.double).toInt() }
            ?: throw MishapInvalidIota.ofType(countIota, 0, "number")
        if (perPointCount < 1 || perPointCount > 4) {
            throw MishapInvalidIota.ofType(countIota, 0, "number between 1 and 4")
        }

        val points = (pointsIota as? ListIota)?.list?.toList()
            ?: throw MishapInvalidIota.ofType(pointsIota, 1, "list")
        if (points.isEmpty()) {
            throw MishapInvalidIota.ofType(pointsIota, 1, "non-empty list")
        }
        if (points.size > MAX_POINTS_PER_CAST) {
            throw MishapInvalidIota.ofType(pointsIota, 1, "list with at most $MAX_POINTS_PER_CAST entries")
        }
        val total = points.size * perPointCount
        if (total > MAX_TOTAL_PARTICLES) {
            throw MishapInvalidIota.ofType(
                pointsIota,
                1,
                "list/count combination totaling at most $MAX_TOTAL_PARTICLES particles"
            )
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val level = caster.serverLevel()

        for (entry in points) {
            val parsed = parseEntry(entry)
            env.assertVecInRange(parsed.first)

            val color = clampColor(parsed.second)
            val dust = DustParticleOptions(Vector3f(color.x.toFloat(), color.y.toFloat(), color.z.toFloat()), 1.0f)
            level.sendParticles(
                dust,
                parsed.first.x,
                parsed.first.y,
                parsed.first.z,
                perPointCount,
                0.01,
                0.01,
                0.01,
                0.0
            )
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun parseEntry(entry: Iota): Pair<Vec3, Vec3> {
        if (entry is Vec3Iota) {
            return Pair(entry.vec3, Vec3(1.0, 1.0, 1.0))
        }
        if (entry is ListIota) {
            val list = entry.list.toList()
            if (list.size >= 2 && list[0] is Vec3Iota && list[1] is Vec3Iota) {
                return Pair((list[0] as Vec3Iota).vec3, (list[1] as Vec3Iota).vec3)
            }
        }

        throw MishapInvalidIota.ofType(entry, 1, "vector or [vector, color vector]")
    }

    private fun clampColor(color: Vec3): Vec3 {
        fun c(v: Double): Double = v.coerceIn(0.0, 1.0)
        return Vec3(c(color.x), c(color.y), c(color.z))
    }
}