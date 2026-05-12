package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.MenuSessionRegistry
import com.bluup.manifestation.server.mishap.MishapMenuOpenLoop
import com.bluup.manifestation.server.mishap.MishapRadialMenuButtonsOnly
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer

/**
 * Hex operator: pop (title, entries-list) and show a radial menu.
 *
 * Radial menus are button-only. Any non-button entry causes a mishap.
 */
object OpCreateRadialMenu : Action {

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()

        val menu = MenuReader.readMenu(stack)
        if (menu.entries.any { !it.isButton }) {
            throw MishapRadialMenuButtonsOnly()
        }

        val caster = env.castingEntity as? ServerPlayer
        if (caster == null) {
            throw MishapRequiresCasterWill()
        }

        val payload = MenuPayload(
            menu.title,
            menu.entries,
            MenuPayload.Layout.RADIAL,
            MenuPayload.Theme.SCHOLAR,
            1,
            env.castingHand,
            MenuDispatchSourceResolver.fromEnvironment(env)
        )
        if (MenuOpenLoopGuard.shouldMishap(caster, payload)) {
            throw MishapMenuOpenLoop()
        }
        val circleContext = if (env is CircleCastEnv) {
            val imp = env.impetus
            if (imp != null) {
                MenuSessionRegistry.CircleContext(caster.serverLevel().dimension().location().toString(), imp.blockPos)
            } else {
                null
            }
        } else {
            null
        }
        ManifestationServer.sendMenuTo(caster, payload, circleContext)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
