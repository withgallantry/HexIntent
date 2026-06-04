package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.UiDropdownIota

/**
 * Constructor operator for typed UI dropdown entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   [string, string, ...]
 */
object OpUiDropdown : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val optionsIota = args[0]
        val label = args[1]

        if (optionsIota !is ListIota) {
            throw MishapInvalidIota.ofType(optionsIota, 1, "list")
        }

        if (!optionsIota.list.nonEmpty) {
            throw MishapInvalidIota.ofType(optionsIota, 1, "non_empty_list")
        }

        val options = mutableListOf<at.petrak.hexcasting.api.casting.iota.Iota>()
        for (option in optionsIota.list) {
            options.add(option)
        }

        return listOf(UiDropdownIota(label, options, 0))
    }
}