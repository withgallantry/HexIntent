package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.common.menu.MenuEntry
import com.bluup.manifestation.common.menu.StoredIota
import com.bluup.manifestation.server.iota.UiButtonIota
import com.bluup.manifestation.server.iota.UiDropdownIota
import com.bluup.manifestation.server.iota.UiInputIota
import com.bluup.manifestation.server.iota.UiSectionIota
import com.bluup.manifestation.server.iota.UiSliderIota
import net.minecraft.network.chat.Component

/**
 * Shared iota-reading logic for the two menu operators.
 *
 * Error policy:
 *   * Title wrong type / missing               -> hard mishap (MishapInvalidIota)
 *   * Outer entry-list wrong type / missing    -> hard mishap
 *   * Individual malformed entry               -> skip, keep going
 *
 * Entry model:
 *   * UiButtonIota(label, actions)
 *   * UiInputIota(label)
 *   * UiSliderIota(label, min, max, current?)
 *   * UiDropdownIota(label, options)
 *   * UiSectionIota(label)
 *
 * This parser is intentionally strict and only accepts those typed iotas.
 * Legacy shape inference is removed.
 */
internal object MenuReader {

    data class ReadResult(val title: Component, val entries: List<MenuEntry>)

    fun readMenu(stack: MutableList<Iota>): ReadResult {
        Manifestation.LOGGER.info(
            "MenuReader.readMenu: stack size = {}, top 4 = {}",
            stack.size,
            stack.takeLast(4).map { "${it::class.simpleName}(${it.display().string})" }
        )

        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        // Fixed contract: top is title, below is buttons-list.
        // This corresponds to input order [buttons-list, title].
        val titleIota = stack.removeAt(stack.lastIndex)
        val buttonsIota = stack.removeAt(stack.lastIndex)

        val title: Component = titleIota.display()
        Manifestation.LOGGER.info(
            "MenuReader: title = '{}' (type {})",
            title.string, titleIota::class.simpleName
        )

        Manifestation.LOGGER.info(
            "MenuReader: buttons-slot iota type {}",
            buttonsIota::class.simpleName
        )
        if (buttonsIota !is ListIota) {
            Manifestation.LOGGER.warn(
                "MenuReader: buttons slot is NOT a ListIota — it is {}. Mishapping.",
                buttonsIota::class.simpleName
            )
            // Restore original order on mishap.
            stack.add(buttonsIota)
            stack.add(titleIota)
            throw MishapInvalidIota.ofType(buttonsIota, 1, "list")
        }

        val entries = mutableListOf<MenuEntry>()
        var index = 0
        for (entryIota in buttonsIota.list) {
            val entry = tryReadEntry(entryIota, index)
            if (entry != null) {
                Manifestation.LOGGER.info(
                    "MenuReader: entry[{}] ACCEPTED — kind={}, label='{}', {} actions",
                    index, entry.kind(), entry.label().string, entry.actions().size
                )
                entries.add(entry)
            } else {
                Manifestation.LOGGER.warn(
                    "MenuReader: entry[{}] REJECTED (see prior warning for reason)",
                    index
                )
            }
            index++
        }

        Manifestation.LOGGER.info(
            "MenuReader: final result — title='{}', {} of {} entries accepted",
            title.string, entries.size, index
        )
        return ReadResult(title, entries)
    }

    fun readColumnCount(stack: MutableList<Iota>): Int {
        if (stack.isEmpty()) {
            throw MishapNotEnoughArgs(1, 0)
        }
        val top = stack.removeAt(stack.lastIndex)
        if (top !is DoubleIota) {
            stack.add(top)
            throw MishapInvalidIota.ofType(top, 0, "double")
        }
        val cols = Math.round(top.double).toInt()
        return cols.coerceIn(1, 10)
    }

    private fun tryReadEntry(entryIota: Iota, index: Int): MenuEntry? {
        return when (entryIota) {
            is UiInputIota -> {
                MenuEntry.input(entryIota.label.display())
            }

            is UiSliderIota -> {
                val current = if (entryIota.hasCurrent()) entryIota.current else null
                MenuEntry.slider(entryIota.label.display(), entryIota.min, entryIota.max, current)
            }

            is UiButtonIota -> {
                val stored = mutableListOf<StoredIota>()
                var actionIndex = 0
                for (action in entryIota.actions) {
                    Manifestation.LOGGER.info(
                        "    button[{}].action[{}]: type = {}",
                        index, actionIndex, action::class.simpleName
                    )
                    stored.add(StoredIota.of(action))
                    actionIndex++
                }

                if (stored.isEmpty()) {
                    Manifestation.LOGGER.warn(
                        "  button[{}]: REJECTED — action-list is empty",
                        index
                    )
                    null
                } else {
                    MenuEntry.button(entryIota.label.display(), stored)
                }
            }

            is UiSectionIota -> {
                MenuEntry.section(entryIota.label.display())
            }

            is UiDropdownIota -> {
                MenuEntry.dropdown(entryIota.label.display(), entryIota.options, entryIota.selectedIndex)
            }

            else -> {
                Manifestation.LOGGER.warn(
                    "MenuReader: entry[{}] rejected because it is not a typed ui iota: {}",
                    index,
                    entryIota::class.simpleName
                )
                null
            }
        }
    }
}
