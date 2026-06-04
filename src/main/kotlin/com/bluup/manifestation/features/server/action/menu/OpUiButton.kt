package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.UiButtonIota
import net.minecraft.network.chat.Component

/**
 * Constructor operator for typed UI button entries.
 *
 * Stack shape on entry (top -> bottom):
 *   label
 *   [action, action, ...]
 */
object OpUiButton : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val actionsIota = args[0]
        val label = args[1]
        if (actionsIota !is ListIota) {
            throw MishapInvalidIota.ofType(actionsIota, 1, "list")
        }
        if (!actionsIota.list.nonEmpty) {
            throw MishapInvalidIota(actionsIota, 1, Component.literal("non-empty list"))
        }

        return listOf(UiButtonIota(label, actionsIota.list.toList()))
    }
}
