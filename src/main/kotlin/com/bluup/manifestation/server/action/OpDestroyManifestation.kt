package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
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
object OpDestroyManifestation : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val centerIota = args[0]
        val radiusIota = args[1]

        val center = (centerIota as? Vec3Iota)?.vec3
            ?: throw MishapInvalidIota.ofType(centerIota, 1, "vector")

        val radius = (radiusIota as? DoubleIota)?.double
            ?: throw MishapInvalidIota.ofType(radiusIota, 0, "number")

        if (radius < 0.0) {
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

        return listOf()
    }

    private fun isManifestationBlock(block: net.minecraft.world.level.block.Block): Boolean {
        return block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK
            || block == ManifestationBlocks.INTENT_RELAY_BLOCK
            || block == ManifestationBlocks.INTENT_RELAY_EMITTER_BLOCK
    }
}