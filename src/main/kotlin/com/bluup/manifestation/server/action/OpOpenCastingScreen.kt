package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import at.petrak.hexcasting.common.msgs.MsgOpenSpellGuiS2C
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.mishap.MishapRequiresStaffInHand
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * Opens HexCasting's spell drawing UI for the casting hand.
 *
 * This is player-only and cannot be invoked by splinter environments.
 */
object OpOpenCastingScreen : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        if (caster is FakePlayer || env is SplinterCastEnv) {
            throw MishapRequiresCasterWill()
        }

        val hand = resolveStaffHand(caster, env.castingHand) ?: throw MishapRequiresStaffInHand()
        val vm = IXplatAbstractions.INSTANCE.getStaffcastVM(caster, hand)
        val patterns = IXplatAbstractions.INSTANCE.getPatternsSavedInUi(caster)
        val descs = vm.generateDescs()
        IXplatAbstractions.INSTANCE.sendPacketToPlayer(
            caster,
            MsgOpenSpellGuiS2C(hand, patterns, descs.first, descs.second, 0)
        )

        val image2 = image.withUsedOp()
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun resolveStaffHand(player: ServerPlayer, preferred: InteractionHand): InteractionHand? {
        if (player.getItemInHand(preferred).`is`(HexTags.Items.STAVES)) {
            return preferred
        }

        val fallback = if (preferred == InteractionHand.MAIN_HAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND
        if (player.getItemInHand(fallback).`is`(HexTags.Items.STAVES)) {
            return fallback
        }

        return null
    }

}
