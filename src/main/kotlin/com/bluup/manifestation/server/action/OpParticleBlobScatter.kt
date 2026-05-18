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
import com.bluup.manifestation.server.iota.ParticleBlobIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer

/**
 * Stack shape on entry (top -> bottom):
 *   lifetime ticks? (number, optional 1..200, default 1)
 *   particle blob
 *   origin vector
 */
object OpParticleBlobScatter : Action {
    private const val MAX_LIFETIME_TICKS = 200

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()

        var lifetimeTicks = 1
        if (stack.size >= 3 && stack.last() is DoubleIota) {
            lifetimeTicks = Math.round((stack.removeAt(stack.lastIndex) as DoubleIota).double).toInt()
        }
        if (lifetimeTicks !in 1..MAX_LIFETIME_TICKS) {
            throw MishapInvalidIota.ofType(DoubleIota(lifetimeTicks.toDouble()), 0, "number between 1 and $MAX_LIFETIME_TICKS")
        }

        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val blobIota = stack.removeAt(stack.lastIndex)
        val originIota = stack.removeAt(stack.lastIndex)

        val origin = (originIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(originIota, 1, "vector")
        env.assertVecInRange(origin)

        val blob = blobIota as? ParticleBlobIota
            ?: throw MishapInvalidIota.ofType(blobIota, 0, "particle blob")
        val points = try {
            ParticleBlobCodec.decode(blob.blob)
        } catch (_: IllegalArgumentException) {
            throw MishapInvalidIota.ofType(blobIota, 0, "valid particle blob")
        }

        // Light safety guard for client playback load.
        if (points.size > ParticleBlobCodec.MAX_POINTS) {
            throw MishapInvalidIota.ofType(blobIota, 0, "particle blob under point budget")
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        ManifestationServer.sendParticleBlobCastTo(caster, origin, blob.blob, lifetimeTicks)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}