package com.bluup.manifestation.server

import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3

/**
 * Dedicated environment for delayed menu dispatches.
 *
 * Menu casts always execute in this environment instead of trying to rebuild
 * every original cast environment type on each button click.
 */
class MenuCastEnv(
    caster: ServerPlayer,
    hand: InteractionHand
) : PlayerBasedCastEnv(caster, hand) {

    override fun getMishapEnvironment(): MishapEnvironment {
        return MenuMishapEnvironment(world, caster)
    }

    override fun isVecInRangeEnvironment(vec: Vec3): Boolean {
        return vec.distanceToSqr(caster.position()) <= MENU_AMBIT_RADIUS_SQ
    }

    override fun mishapSprayPos(): Vec3 = caster.position()

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        if (caster.isCreative) {
            return 0L
        }
        return extractMediaFromInventory(cost, canOvercast(), simulate)
    }

    override fun getPigment(): FrozenPigment {
        return IXplatAbstractions.INSTANCE.getPigment(caster)
    }

    private class MenuMishapEnvironment(world: net.minecraft.server.level.ServerLevel, player: ServerPlayer?) :
        MishapEnvironment(world, player) {
        override fun yeetHeldItemsTowards(targetPos: Vec3) {}

        override fun dropHeldItems() {}

        override fun drown() {}

        override fun damage(healthProportion: Float) {}

        override fun removeXp(amount: Int) {}

        override fun blind(ticks: Int) {}
    }

    companion object {
        private const val MENU_AMBIT_RADIUS = 32.0
        private const val MENU_AMBIT_RADIUS_SQ = MENU_AMBIT_RADIUS * MENU_AMBIT_RADIUS
    }
}