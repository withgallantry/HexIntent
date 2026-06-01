package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.BooleanIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.UiCheckboxIota

/**
 * Constructor operator for typed checkbox entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   checked
 */
object OpUiCheckbox : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val checkedIota = args[0]
        val label = args[1]
        val checked = (checkedIota as? BooleanIota)?.bool
            ?: throw MishapInvalidIota.ofType(checkedIota, 1, "boolean")

        return listOf(UiCheckboxIota(label, checked))
    }
}
