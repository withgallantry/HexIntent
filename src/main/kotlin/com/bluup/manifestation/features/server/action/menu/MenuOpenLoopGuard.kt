package com.bluup.manifestation.server.action

import com.bluup.manifestation.common.menu.MenuEntry
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.server.ManifestationConfig
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Detects rapid repeated opens of the same menu for a player.
 *
 * This specifically protects against looping circles repeatedly invoking
 * CREATE_LIST_MENU / CREATE_GRID_MENU with an identical payload.
 */
object MenuOpenLoopGuard {
    private data class PlayerOpenState(
        val menuSignature: Int,
        val dimensionId: String,
        val firstSeenAtMs: Long,
        val count: Int
    )

    private val recentByPlayer: MutableMap<UUID, PlayerOpenState> = mutableMapOf()

    @JvmStatic
    fun clearForPlayer(playerId: UUID) {
        recentByPlayer.remove(playerId)
    }

    /**
     * @return true if this open attempt should mishap due to burst repeats.
     */
    fun shouldMishap(player: ServerPlayer, payload: MenuPayload, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = player.uuid
        val menuSig = signatureOf(payload)
        val dimId = player.serverLevel().dimension().location().toString()
        val windowMs = ManifestationConfig.menuOpenLoopWindowMs()
        val triggerCount = ManifestationConfig.menuOpenLoopTriggerCount()
        val previous = recentByPlayer[key]

        if (
            previous == null ||
            previous.menuSignature != menuSig ||
            previous.dimensionId != dimId ||
            nowMs - previous.firstSeenAtMs > windowMs
        ) {
            recentByPlayer[key] = PlayerOpenState(menuSig, dimId, nowMs, 1)
            return false
        }

        val nextCount = previous.count + 1
        recentByPlayer[key] = previous.copy(count = nextCount)
        return nextCount >= triggerCount
    }

    private fun signatureOf(menu: MenuPayload): Int {
        var sig = menu.title().string.hashCode()
        sig = 31 * sig + menu.layout().ordinal
        sig = 31 * sig + menu.theme().ordinal
        sig = 31 * sig + menu.columns()
        sig = 31 * sig + menu.hand().ordinal
        sig = 31 * sig + menu.entries().size

        for (entry: MenuEntry in menu.entries()) {
            sig = 31 * sig + entry.kind().ordinal
            sig = 31 * sig + entry.label().string.hashCode()
            if (entry.isButton()) {
                sig = 31 * sig + entry.actions().size
                for (stored in entry.actions()) {
                    sig = 31 * sig + stored.tag().hashCode()
                }
            }
            if (entry.isSlider()) {
                sig = 31 * sig + java.lang.Double.hashCode(entry.sliderMin())
                sig = 31 * sig + java.lang.Double.hashCode(entry.sliderMax())
                sig = 31 * sig + java.lang.Double.hashCode(entry.sliderCurrent())
            }
            if (entry.isNumericInput()) {
                sig = 31 * sig + if (entry.numericHasCurrent()) 1 else 0
                sig = 31 * sig + java.lang.Double.hashCode(entry.numericCurrent())
            }
            if (entry.isCheckbox()) {
                sig = 31 * sig + if (entry.checkboxChecked()) 1 else 0
            }
            if (entry.isSelectList()) {
                sig = 31 * sig + entry.selectMaxRows()
                sig = 31 * sig + if (entry.selectMulti()) 1 else 0
                sig = 31 * sig + entry.selectOptions().size
                for (stored in entry.selectOptions()) {
                    sig = 31 * sig + stored.tag().hashCode()
                }
            }
            if (entry.isDropdown()) {
                sig = 31 * sig + entry.dropdownSelected()
                for (option in entry.dropdownOptions()) {
                    sig = 31 * sig + option.string.hashCode()
                }
            }
        }

        return sig
    }
}