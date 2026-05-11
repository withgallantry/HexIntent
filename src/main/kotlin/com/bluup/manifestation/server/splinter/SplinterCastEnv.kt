package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv
import at.petrak.hexcasting.api.casting.circles.BlockEntityAbstractImpetus
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.Vec3
import java.util.UUID

open class SplinterCastEnv(
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

class CircleSplinterCastEnv(
    caster: ServerPlayer,
    hand: InteractionHand,
    splinterOrigin: Vec3,
    sourceSplinterId: UUID,
    private val impetusPos: BlockPos
) : SplinterCastEnv(caster, hand, splinterOrigin, sourceSplinterId) {
    override fun sendMishapMsgToPlayer(mishap: OperatorSideEffect.DoMishap) {
        val msg = mishap.mishap.errorMessageWithName(this, mishap.errorCtx) ?: return
        val center = Vec3.atCenterOf(impetusPos)
        val maxDistSq = 32.0 * 32.0
        for (player in world.players()) {
            if (player.position().distanceToSqr(center) <= maxDistSq) {
                player.sendSystemMessage(msg)
            }
        }
    }

    override fun isVecInRangeEnvironment(vec: Vec3): Boolean {
        if (super.isVecInRangeEnvironment(vec)) {
            return true
        }

        val impetus = world.getBlockEntity(impetusPos) as? BlockEntityAbstractImpetus ?: return false
        val state = impetus.executionState ?: return false
        return state.bounds.contains(vec)
    }

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        val blockEntity = world.getBlockEntity(impetusPos) as? BlockEntityAbstractImpetus ?: return cost
        val mediaAvailable = blockEntity.media
        if (mediaAvailable < 0) {
            return 0L
        }

        val mediaToTake = kotlin.math.min(cost, mediaAvailable)
        val remaining = cost - mediaToTake
        if (!simulate) {
            blockEntity.setMedia(mediaAvailable - mediaToTake)
        }
        return remaining
    }
}
