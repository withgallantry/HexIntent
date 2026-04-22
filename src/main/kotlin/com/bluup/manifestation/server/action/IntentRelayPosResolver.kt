package com.bluup.manifestation.server.action

import com.bluup.manifestation.server.block.ManifestationBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel

/**
 * Resolves a relay position from a candidate vector-derived block pos.
 *
 * If the candidate points at the support block (common with raycasts), this
 * searches adjacent blocks for an attached intent relay that uses that support.
 */
object IntentRelayPosResolver {

    fun resolve(level: ServerLevel, candidate: BlockPos): BlockPos? {
        if (level.getBlockState(candidate).block == ManifestationBlocks.INTENT_RELAY_BLOCK) {
            return candidate
        }

        val adjacentMatches = mutableListOf<BlockPos>()
        for (dir in Direction.values()) {
            val neighbor = candidate.relative(dir)
            val state = level.getBlockState(neighbor)
            if (state.block != ManifestationBlocks.INTENT_RELAY_BLOCK) {
                continue
            }
            adjacentMatches.add(neighbor)
        }

        return if (adjacentMatches.size == 1) adjacentMatches[0] else null
    }
}
