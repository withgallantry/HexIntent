package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import com.bluup.manifestation.server.iota.UiSectionIota

/**
 * Constructor operator for typed UI section entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 */
object OpUiSection : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        return listOf(UiSectionIota(args[0]))
    }
}