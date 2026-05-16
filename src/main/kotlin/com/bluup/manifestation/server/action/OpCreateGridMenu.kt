package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.common.menu.MenuPayload
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.MenuSessionRegistry
import com.bluup.manifestation.server.mishap.MishapMenuOpenLoop
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.fabricmc.fabric.api.entity.FakePlayer
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
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        if (caster is FakePlayer) {
            throw MishapRequiresCasterWill()
        }
        val payload = MenuPayload(
            menu.title,
            menu.entries,
            MenuPayload.Layout.GRID,
            MenuPayload.Theme.SCHOLAR,
            columns,
            env.castingHand,
            MenuDispatchSourceResolver.fromEnvironment(env)
        )
        if (MenuOpenLoopGuard.shouldMishap(caster, payload)) throw MishapMenuOpenLoop()
        val sessionStack = stack.toList()
        val sessionRavenmind = if (image.userData.contains(HexAPI.RAVENMIND_USERDATA)) {
            image.userData.getCompound(HexAPI.RAVENMIND_USERDATA).copy()
        } else null
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
        ManifestationServer.sendMenuTo(caster, payload, circleContext, sessionStack, sessionRavenmind)
        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
