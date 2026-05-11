package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.BooleanIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.iota.UiCheckboxIota

/**
 * Constructor operator for typed checkbox entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   checked
 */
object OpUiCheckbox : Action {
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
        val checkedIota = stack.removeAt(stack.lastIndex)
        val checked = (checkedIota as? BooleanIota)?.bool ?: run {
            stack.add(checkedIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(checkedIota, 1, "boolean")
        }

        stack.add(UiCheckboxIota(label, checked))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
