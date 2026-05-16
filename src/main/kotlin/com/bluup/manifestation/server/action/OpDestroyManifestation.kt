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
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer

/**
 * Destroy manifestation blocks in a radius around a center vector.
 *
 * Stack shape on entry (top -> bottom):
 *   radius
 *   center vector
 */
object OpDestroyManifestation : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val radiusIota = stack.removeAt(stack.lastIndex)
        val centerIota = stack.removeAt(stack.lastIndex)

        val center = if (centerIota is Vec3Iota) {
            centerIota.vec3
        } else {
            stack.add(centerIota)
            stack.add(radiusIota)
            throw MishapInvalidIota.ofType(centerIota, 1, "vector")
        }

        val radius = if (radiusIota is DoubleIota) {
            radiusIota.double
        } else {
            stack.add(centerIota)
            stack.add(radiusIota)
            throw MishapInvalidIota.ofType(radiusIota, 0, "number")
        }

        if (radius < 0.0) {
            stack.add(centerIota)
            stack.add(radiusIota)
            throw MishapInvalidIota.ofType(radiusIota, 0, "non-negative number")
        }

        env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val world = env.world
        val centerPos = BlockPos.containing(center)
        val maxOffset = kotlin.math.ceil(radius).toInt()
        val radiusSq = radius * radius

        for (dx in -maxOffset..maxOffset) {
            for (dy in -maxOffset..maxOffset) {
                for (dz in -maxOffset..maxOffset) {
                    val candidate = centerPos.offset(dx, dy, dz)
                    if (candidate.distToCenterSqr(center.x, center.y, center.z) > radiusSq) {
                        continue
                    }

                    val state = world.getBlockState(candidate)
                    if (!isManifestationBlock(state.block)) {
                        continue
                    }

                    // Manifestation cleanup must work even inside claimed chunks.
                    world.removeBlock(candidate, false)
                }
            }
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun isManifestationBlock(block: net.minecraft.world.level.block.Block): Boolean {
        return block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK
            || block == ManifestationBlocks.INTENT_RELAY_BLOCK
            || block == ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK
    }
}