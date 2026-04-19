package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.mishap.MishapMenuOpenLoop
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer

/**
 * Hex operator: pop (columns, title, buttons-list) from the stack and show
 * the caster a grid-layout menu.
 *
 * Stack shape on entry (top → bottom):
 *   columns (number iota — will be clamped to 1..10)
 *   title
 *   [ button, button, ... ]
 *
 * Otherwise identical to OpCreateListMenu. Using a separate operator rather
 * than a columns-aware single operator keeps each trigger pattern's stack
 * contract explicit — a player looking at a pattern name can see "oh, grid,
 * so I need columns" without surprise.
 */
object OpCreateGridMenu : Action {

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()

        val columns = MenuReader.readColumnCount(stack)
        val menu = MenuReader.readMenu(stack)

        val caster = env.castingEntity as? ServerPlayer
        if (caster == null) {
            throw MishapRequiresCasterWill()
        }

        val payload = MenuPayload(
            menu.title,
            menu.entries,
            MenuPayload.Layout.GRID,
            MenuPayload.Theme.SCHOLAR,
            columns,
            env.castingHand
        )
        if (MenuOpenLoopGuard.shouldMishap(caster, payload)) {
            throw MishapMenuOpenLoop()
        }
        ManifestationServer.sendMenuTo(caster, payload)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
