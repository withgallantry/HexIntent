package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack

/**
 * Per-item charm cast sound override flags persisted on the item stack.
 */
object CharmCastSoundOverrides {
    const val TAG_MUTE_CAST_SOUND = "manifestation_charm_cast_sound_muted"
    const val TAG_CAST_SOUND_ID = "manifestation_charm_cast_sound_id"

    fun hasConfiguredCastSoundOverride(stack: ItemStack): Boolean {
        if (KotlinNbtCompat.getBoolean(stack, TAG_MUTE_CAST_SOUND)) {
            return true
        }

        return KotlinNbtCompat.getString(stack, TAG_CAST_SOUND_ID).isNotBlank()
    }

    @JvmStatic
    fun isCharmedStack(stack: ItemStack): Boolean = CharmItemInterop.isCharmedStack(stack)

    @JvmStatic
    fun isHexicalCharmedStack(stack: ItemStack): Boolean = isCharmedStack(stack)

    fun setMuted(stack: ItemStack, muted: Boolean) {
        KotlinNbtCompat.putBoolean(stack, TAG_MUTE_CAST_SOUND, muted)
        if (muted) {
            KotlinNbtCompat.remove(stack, TAG_CAST_SOUND_ID)
        }
    }

    fun setSoundId(stack: ItemStack, soundId: String) {
        KotlinNbtCompat.putBoolean(stack, TAG_MUTE_CAST_SOUND, false)
        KotlinNbtCompat.putString(stack, TAG_CAST_SOUND_ID, soundId)
    }

    /**
     * Returns true if cast sound was fully handled (muted or custom sound played).
     */
    fun handlePostCastSound(player: ServerPlayer, world: ServerLevel, stack: ItemStack): Boolean {
        if (KotlinNbtCompat.getBoolean(stack, TAG_MUTE_CAST_SOUND)) {
            return true
        }

        val soundId = KotlinNbtCompat.getString(stack, TAG_CAST_SOUND_ID)
        if (soundId.isBlank()) {
            return false
        }

        val loc = ResourceLocation.tryParse(soundId) ?: return false
        val sound = BuiltInRegistries.SOUND_EVENT.getOptional(loc)
        if (sound.isEmpty) {
            Manifestation.LOGGER.warn("Invalid configured charm cast sound id '{}'; falling back to default charm behavior", soundId)
            return false
        }

        world.playSound(null, player.x, player.y, player.z, sound.get(), SoundSource.PLAYERS, 1.0f, 1.0f)
        return true
    }
}
