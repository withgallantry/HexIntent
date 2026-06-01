package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.UiSliderIota

/**
 * Constructor operator for typed UI slider entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   current
 *   max
 *   min
 */
object OpUiSlider : ConstMediaAction {
    override val argc = 4

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val minIota = args[0]
        val maxIota = args[1]
        val currentIota = args[2]
        val label = args[3]

        val min = (minIota as? DoubleIota)?.double ?: run {
            throw MishapInvalidIota.ofType(minIota, 3, "double")
        }
        val max = (maxIota as? DoubleIota)?.double ?: run {
            throw MishapInvalidIota.ofType(maxIota, 2, "double")
        }
        val current = (currentIota as? DoubleIota)?.double ?: run {
            throw MishapInvalidIota.ofType(currentIota, 1, "double")
        }

        if (min > max) {
            throw MishapInvalidIota.of(DoubleIota(min), 3, "double.between", min, max)
        }
        if (current < min || current > max) {
            throw MishapInvalidIota.of(DoubleIota(current), 1, "double.between", min, max)
        }

        return listOf(UiSliderIota(label, min, max, current))
    }
}
