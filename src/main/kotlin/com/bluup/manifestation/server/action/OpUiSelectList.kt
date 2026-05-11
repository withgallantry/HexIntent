package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.BooleanIota
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.iota.UiSelectListIota

/**
 * Constructor operator for typed selectable list entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   max rows
 *   [option, option, ...]
 *   multi select? (optional boolean, defaults false)
 */
object OpUiSelectList : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 3) {
            throw MishapNotEnoughArgs(3, stack.size)
        }

        val label = stack.removeAt(stack.lastIndex)
        val maxRowsIota = stack.removeAt(stack.lastIndex)
        val optionsIota = stack.removeAt(stack.lastIndex)

        val maxRows = (maxRowsIota as? DoubleIota)?.double?.toInt() ?: run {
            stack.add(optionsIota)
            stack.add(maxRowsIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(maxRowsIota, 1, "double")
        }

        if (optionsIota !is ListIota) {
            stack.add(optionsIota)
            stack.add(maxRowsIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(optionsIota, 2, "list")
        }

        if (!optionsIota.list.nonEmpty) {
            stack.add(optionsIota)
            stack.add(maxRowsIota)
            stack.add(label)
            throw MishapInvalidIota.ofType(optionsIota, 2, "non_empty_list")
        }

        val multiSelect = if (stack.isNotEmpty() && stack[stack.lastIndex] is BooleanIota) {
            (stack.removeAt(stack.lastIndex) as BooleanIota).bool
        } else {
            false
        }

        val maxRowsClamped = maxRows.coerceIn(1, 12)
        stack.add(UiSelectListIota(label, optionsIota.list.toList(), maxRowsClamped, multiSelect))

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
