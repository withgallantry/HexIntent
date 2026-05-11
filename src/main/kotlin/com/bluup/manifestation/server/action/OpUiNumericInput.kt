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
import com.bluup.manifestation.server.iota.UiNumericInputIota

/**
 * Constructor operator for typed numeric text input entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   current
 */
object OpUiNumericInput : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val label = stack.removeAt(stack.lastIndex)
        val currentIota = stack.removeAt(stack.lastIndex)
        val current = (currentIota as? DoubleIota)?.double ?: run {
            stack.add(currentIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(currentIota, 1, "double")
        }

        stack.add(UiNumericInputIota(label, current))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
