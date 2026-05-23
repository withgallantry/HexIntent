package com.bluup.manifestation.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks one-shot cast-sound suppression requests per player.
 *
 * The operator arms suppression for the remainder of the current cast batch.
 * Suppression state is cleared when the cast batch ends.
 */
object CastSoundSuppressor {
    private val mutedForBatch: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun armNextCast(playerId: UUID) {
        mutedForBatch.add(playerId)
    }

    fun shouldSuppressCurrentExecution(playerId: UUID): Boolean {
        return mutedForBatch.contains(playerId)
    }

    fun clearForPlayer(playerId: UUID) {
        mutedForBatch.remove(playerId)
    }
}
