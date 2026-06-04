package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.mod.HexTags
import at.petrak.hexcasting.xplat.IXplatAbstractions
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.mishap.MishapRequiresStaffInHand
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * Clears the player's persisted staff stack session.
 *
 * This is player-only and cannot be invoked by splinter environments.
 */
object OpClearStack : ConstMediaAction {
    override val argc = 0

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        if (caster is FakePlayer || env is SplinterCastEnv) {
            throw MishapRequiresCasterWill()
        }

        if (resolveStaffHand(caster, env.castingHand) == null) {
            throw MishapRequiresStaffInHand()
        }

        IXplatAbstractions.INSTANCE.setStaffcastImage(caster, null)
        IXplatAbstractions.INSTANCE.setPatterns(caster, listOf())

        return listOf()
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
