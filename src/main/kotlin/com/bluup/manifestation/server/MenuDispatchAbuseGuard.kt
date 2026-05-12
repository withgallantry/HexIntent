package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Lightweight anti-abuse guard for menu dispatch packets.
 *
 * Designed to be invisible during normal play while throttling synthetic
 * packet spam before expensive iota deserialization and execution.
 */
object MenuDispatchAbuseGuard {
    private const val STALE_ENTRY_MS = 10 * 60_000L
    private const val LOG_THROTTLE_MS = 5_000L
    private const val MAX_VIOLATION_LEVEL = 8

    private var checksSinceCleanup = 0
    private val states = HashMap<UUID, State>()

    fun clearForPlayer(playerId: UUID) {
        states.remove(playerId)
    }

    fun shouldAllow(player: ServerPlayer): Boolean {
        val now = System.nanoTime()
        val refillPerSecond = ManifestationConfig.menuDispatchRefillPerSecond()
        val burstTokens = ManifestationConfig.menuDispatchBurstTokens()
        val violationDecayMs = ManifestationConfig.menuDispatchViolationDecayMs()
        val baseCooldownMs = ManifestationConfig.menuDispatchBaseCooldownMs()
        val maxCooldownMs = ManifestationConfig.menuDispatchMaxCooldownMs()

        cleanupIfNeeded(now)

        val state = states.getOrPut(player.uuid) {
            State(
            tokens = burstTokens,
                lastRefillNanos = now,
                cooldownUntilNanos = 0L,
                violationLevel = 0,
                lastViolationNanos = 0L,
                lastSeenNanos = now,
                lastLogNanos = 0L
            )
        }
        state.lastSeenNanos = now

        refill(state, now, refillPerSecond, burstTokens)

        if (now < state.cooldownUntilNanos) {
            maybeLog(player, state, now)
            return false
        }

        if (state.tokens >= 1.0) {
            state.tokens -= 1.0
            return true
        }

        // Decay violation level after sustained good behavior.
        if (state.lastViolationNanos > 0L) {
            val sinceLastViolationMs = nanosToMillis(now - state.lastViolationNanos)
            if (sinceLastViolationMs >= violationDecayMs) {
                state.violationLevel = (state.violationLevel - 1).coerceAtLeast(0)
            }
        }

        state.violationLevel = (state.violationLevel + 1).coerceAtMost(MAX_VIOLATION_LEVEL)
        state.lastViolationNanos = now

        val exponent = (state.violationLevel - 1).coerceIn(0, 4)
        val scaledCooldownMs = baseCooldownMs * (1L shl exponent)
        val cooldownMs = scaledCooldownMs.coerceAtMost(maxCooldownMs)
        state.cooldownUntilNanos = now + millisToNanos(cooldownMs)

        maybeLog(player, state, now)
        return false
    }

    private fun refill(state: State, now: Long, refillPerSecond: Double, burstTokens: Double) {
        val elapsed = now - state.lastRefillNanos
        if (elapsed <= 0L) {
            return
        }

        val restored = elapsed * refillPerSecond / 1_000_000_000.0
        state.tokens = (state.tokens + restored).coerceAtMost(burstTokens)
        state.lastRefillNanos = now
    }

    private fun cleanupIfNeeded(now: Long) {
        checksSinceCleanup++
        if (checksSinceCleanup < 256) {
            return
        }

        checksSinceCleanup = 0
        val staleNanos = millisToNanos(STALE_ENTRY_MS)
        val iter = states.entries.iterator()
        while (iter.hasNext()) {
            val (_, state) = iter.next()
            if (now - state.lastSeenNanos > staleNanos) {
                iter.remove()
            }
        }
    }

    private fun maybeLog(player: ServerPlayer, state: State, now: Long) {
        val sinceLastLogMs = nanosToMillis(now - state.lastLogNanos)
        if (sinceLastLogMs < LOG_THROTTLE_MS) {
            return
        }

        state.lastLogNanos = now
        val remainingMs = nanosToMillis((state.cooldownUntilNanos - now).coerceAtLeast(0L))
        Manifestation.LOGGER.warn(
            "Manifestation dispatch: throttling menu actions for {} (cooldown={}ms, violationLevel={})",
            player.name.string,
            remainingMs,
            state.violationLevel
        )
    }

    private fun millisToNanos(ms: Long): Long = ms * 1_000_000L

    private fun nanosToMillis(ns: Long): Long = ns / 1_000_000L

    private data class State(
        var tokens: Double,
        var lastRefillNanos: Long,
        var cooldownUntilNanos: Long,
        var violationLevel: Int,
        var lastViolationNanos: Long,
        var lastSeenNanos: Long,
        var lastLogNanos: Long
    )
}