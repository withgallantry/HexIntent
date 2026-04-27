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
 * Hex operator: pop (title, buttons-list) from the stack and show the caster
 * a vertical list menu assembled from them.
 *
 * Stack shape on entry (top → bottom):
 *   title
 *   [ button, button, ... ]
 *
 * where each button is  [[pattern, pattern, ...], label] .
 *
 * Consumes its arguments. Pushes nothing back.
 */
object OpCreateListMenu : Action {

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()

        val menu = MenuReader.readMenu(stack)

        val caster = env.castingEntity as? ServerPlayer
        if (caster == null) {
            throw MishapRequiresCasterWill()
        }

        val payload = MenuPayload(
            menu.title,
            menu.entries,
            MenuPayload.Layout.LIST,
            MenuPayload.Theme.RITUAL,
            1, // unused for LIST; carry a sentinel so the payload is valid
            env.castingHand,
            MenuDispatchSourceResolver.fromEnvironment(env)
        )
        if (MenuOpenLoopGuard.shouldMishap(caster, payload)) {
            throw MishapMenuOpenLoop()
        }
        ManifestationServer.sendMenuTo(caster, payload)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
