package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
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
object OpUiSlider : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 4) {
            throw MishapNotEnoughArgs(4, stack.size)
        }

        val label = stack.removeAt(stack.lastIndex)
        val currentIota = stack.removeAt(stack.lastIndex)
        val maxIota = stack.removeAt(stack.lastIndex)
        val minIota = stack.removeAt(stack.lastIndex)

        val min = (minIota as? DoubleIota)?.double ?: run {
            stack.add(minIota)
            stack.add(maxIota)
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(minIota, 3, "double")
        }
        val max = (maxIota as? DoubleIota)?.double ?: run {
            stack.add(minIota)
            stack.add(maxIota)
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(maxIota, 2, "double")
        }
        val current = (currentIota as? DoubleIota)?.double ?: run {
            stack.add(minIota)
            stack.add(maxIota)
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(currentIota, 1, "double")
        }

        if (min > max) {
            stack.add(minIota)
            stack.add(maxIota)
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.of(DoubleIota(min), 3, "double.between", min, max)
        }
        if (current < min || current > max) {
            stack.add(minIota)
            stack.add(maxIota)
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.of(DoubleIota(current), 1, "double.between", min, max)
        }

        stack.add(UiSliderIota(label, min, max, current))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
