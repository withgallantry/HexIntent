package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import java.util.UUID

class SplinterCastEnv(
    caster: ServerPlayer,
    private val hand: InteractionHand,
    val splinterOrigin: Vec3,
    val sourceSplinterId: UUID
) : PlayerBasedCastEnv(caster, hand) {

    override fun getMishapEnvironment(): MishapEnvironment = NoopMishapEnvironment(world, caster)

    override fun sendMishapMsgToPlayer(mishap: at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect.DoMishap) {
        // Keep player feedback for splinter mishaps, but suppress punitive effects via NoopMishapEnvironment.
        super.sendMishapMsgToPlayer(mishap)
    }

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        if (caster.isCreative) {
            return 0L
        }
        return extractMediaFromInventory(cost, canOvercast(), simulate)
    }

    override fun getCastingHand(): InteractionHand = hand

    override fun getPigment(): FrozenPigment = IXplatAbstractions.INSTANCE.getPigment(caster)

    override fun isVecInRangeEnvironment(vec: Vec3): Boolean {
        return vec.distanceToSqr(splinterOrigin) <= SPLINTER_AMBIT_RADIUS_SQ + RANGE_EPSILON
    }

    override fun mishapSprayPos(): Vec3 = splinterOrigin

    companion object {
        const val SPLINTER_AMBIT_RADIUS = 16.0
        private const val SPLINTER_AMBIT_RADIUS_SQ = SPLINTER_AMBIT_RADIUS * SPLINTER_AMBIT_RADIUS
        private const val RANGE_EPSILON = 1.0e-11
    }

    private class NoopMishapEnvironment(world: ServerLevel, player: ServerPlayer?) : MishapEnvironment(world, player) {
        override fun yeetHeldItemsTowards(targetPos: Vec3) {}

        override fun dropHeldItems() {}

        override fun drown() {}

        override fun damage(healthProportion: Float) {}

        override fun removeXp(amount: Int) {}

        override fun blind(ticks: Int) {}
    }
}
