package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.UiNumericInputIota

/**
 * Constructor operator for typed numeric text input entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   current
 */
object OpUiNumericInput : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val currentIota = args[0]
        val label = args[1]
        val current = (currentIota as? DoubleIota)?.double
            ?: throw MishapInvalidIota.ofType(currentIota, 1, "double")

        return listOf(UiNumericInputIota(label, current))
    }
}
