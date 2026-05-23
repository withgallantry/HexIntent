package com.bluup.manifestation.server

import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

/**
 * Plays a soft custom cast sound for manifestation staffs with a short cooldown,
 * so frequent casting stays readable without becoming fatiguing.
 */
object StaffCastSoundController {
    fun playIfDue(player: ServerPlayer) {
        val level = player.serverLevel()
        val pitch = (0.86f + (level.random.nextFloat() - 0.5f) * 0.08f).coerceIn(0.78f, 0.94f)
        val volume = 0.22f + level.random.nextFloat() * 0.05f
        level.playSound(
            null,
            player.x,
            player.y,
            player.z,
            SoundEvents.AMETHYST_BLOCK_CHIME,
            SoundSource.PLAYERS,
            volume,
            pitch
        )
    }

    fun clearForPlayer(@Suppress("UNUSED_PARAMETER") playerId: UUID) {
        // No-op: custom cast audio no longer tracks per-player cooldown state.
    }
}
