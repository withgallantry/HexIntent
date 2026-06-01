package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import java.lang.reflect.Method

/**
 * Per-item charm cast sound override flags persisted on the item stack.
 */
object CharmCastSoundOverrides {
    private const val HEXICAL_CHARM_UTIL_CLASS = "miyucomics.hexical.features.charms.CharmUtilities"

    private val hexicalIsStackCharmedMethod: Method? by lazy {
        if (!InteropFlags.HEXICAL_INTEROP) {
            return@lazy null
        }

        try {
            val utilClass = Class.forName(HEXICAL_CHARM_UTIL_CLASS)
            utilClass.getMethod("isStackCharmed", ItemStack::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    const val TAG_MUTE_CAST_SOUND = "manifestation_charm_cast_sound_muted"
    const val TAG_CAST_SOUND_ID = "manifestation_charm_cast_sound_id"

    fun isHexicalCharmedStack(stack: ItemStack): Boolean {
        val method = hexicalIsStackCharmedMethod
        if (method != null) {
            return try {
                (method.invoke(null, stack) as? Boolean) == true
            } catch (_: Throwable) {
                false
            }
        }

        // If Hexical is not present or API changed, only trust explicit charm-tagged stacks.
        return stack.tag?.contains("charmed") == true
    }

    fun setMuted(stack: ItemStack, muted: Boolean) {
        val tag = stack.orCreateTag
        tag.putBoolean(TAG_MUTE_CAST_SOUND, muted)
        if (muted) {
            tag.remove(TAG_CAST_SOUND_ID)
        }
    }

    fun setSoundId(stack: ItemStack, soundId: String) {
        val tag = stack.orCreateTag
        tag.putBoolean(TAG_MUTE_CAST_SOUND, false)
        tag.putString(TAG_CAST_SOUND_ID, soundId)
    }

    /**
     * Returns true if cast sound was fully handled (muted or custom sound played).
     */
    fun handlePostCastSound(player: ServerPlayer, world: ServerLevel, stack: ItemStack): Boolean {
        val tag = stack.tag ?: return false
        if (tag.getBoolean(TAG_MUTE_CAST_SOUND)) {
            return true
        }

        val soundId = tag.getString(TAG_CAST_SOUND_ID)
        if (soundId.isBlank()) {
            return false
        }

        val loc = ResourceLocation.tryParse(soundId) ?: return false
        val sound = BuiltInRegistries.SOUND_EVENT.getOptional(loc)
        if (sound.isEmpty) {
            Manifestation.LOGGER.warn("Invalid configured charm cast sound id '{}'; falling back to default curio behavior", soundId)
            return false
        }

        world.playSound(null, player.x, player.y, player.z, sound.get(), SoundSource.PLAYERS, 1.0f, 1.0f)
        return true
    }
}
