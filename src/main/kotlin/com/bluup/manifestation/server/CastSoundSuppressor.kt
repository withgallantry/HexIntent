package com.bluup.manifestation.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks one-shot cast-sound suppression requests per player.
 *
 * The operator arms with two steps so the pattern that armed it is not muted,
 * and the following pattern execution is muted once.
 */
object CastSoundSuppressor {
    private const val ARMING_STEPS = 2
    private val pendingByPlayer: MutableMap<UUID, Int> = ConcurrentHashMap()

    fun armNextCast(playerId: UUID) {
        pendingByPlayer.compute(playerId) { _, existing ->
            maxOf(existing ?: 0, ARMING_STEPS)
        }
    }

    fun shouldSuppressCurrentExecution(playerId: UUID): Boolean {
        val state = pendingByPlayer[playerId] ?: return false
        return when {
            state <= 0 -> {
                pendingByPlayer.remove(playerId)
                false
            }

            state > 1 -> {
                pendingByPlayer[playerId] = state - 1
                false
            }

            else -> {
                pendingByPlayer.remove(playerId)
                true
            }
        }
    }

    fun clearForPlayer(playerId: UUID) {
        pendingByPlayer.remove(playerId)
    }
}
