package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.iota.ParticleBlobIota
import net.minecraft.world.phys.Vec3

/**
 * Stack shape on entry (top -> bottom):
 *   points list, each entry is vec or [vec, color vec]
 *
 * Pushes one ParticleBlob iota.
 */
object OpMakeParticleBlob : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.isEmpty()) {
            throw MishapNotEnoughArgs(1, stack.size)
        }

        val raw = stack.removeAt(stack.lastIndex)
        val list = (raw as? ListIota)?.list?.toList()
            ?: throw MishapInvalidIota.ofType(raw, 0, "list")
        if (list.isEmpty()) {
            throw MishapInvalidIota.ofType(raw, 0, "non-empty list")
        }
        if (list.size > ParticleBlobCodec.MAX_POINTS) {
            throw MishapInvalidIota.ofType(raw, 0, "list up to ${ParticleBlobCodec.MAX_POINTS} entries")
        }

        val points = list.map { parsePoint(it) }
        val blob = try {
            ParticleBlobCodec.encode(points)
        } catch (_: IllegalArgumentException) {
            throw MishapInvalidIota.ofType(raw, 0, "compressible particle list under blob size limit")
        }

        val weight = ParticleBlobCodec.computeVirtualWeight(points.size, blob.size)
        stack.add(ParticleBlobIota(blob, points.size, weight))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun parsePoint(entry: Iota): ParticleBlobCodec.Point {
        if (entry is Vec3Iota) {
            return ParticleBlobCodec.Point(entry.vec3, Vec3(1.0, 1.0, 1.0))
        }
        if (entry is ListIota) {
            val parts = entry.list.toList()
            if (parts.size >= 2 && parts[0] is Vec3Iota && parts[1] is Vec3Iota) {
                val offset = (parts[0] as Vec3Iota).vec3
                val color = clampColor((parts[1] as Vec3Iota).vec3)
                return ParticleBlobCodec.Point(offset, color)
            }
        }

        throw MishapInvalidIota.ofType(entry, 0, "vector or [vector, color vector]")
    }

    private fun clampColor(color: Vec3): Vec3 {
        fun c(v: Double): Double = v.coerceIn(0.0, 1.0)
        return Vec3(c(color.x), c(color.y), c(color.z))
    }
}