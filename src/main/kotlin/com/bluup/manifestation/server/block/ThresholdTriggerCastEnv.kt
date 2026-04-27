package com.bluup.manifestation.server.block

import at.petrak.hexcasting.api.casting.ParticleSpray
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.MishapEnvironment
import at.petrak.hexcasting.api.pigment.FrozenPigment
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.function.Predicate

/**
 * Restricted environment for threshold-triggered pattern execution.
 *
 * The environment keeps world-edit permissions disabled, but when the triggering
 * entity is a player it exposes that player's held/inventory context so normal
 * player-targeted spell paths can resolve like an in-hand cast.
 */
class ThresholdTriggerCastEnv(
    world: ServerLevel,
    private val origin: BlockPos,
    private val mediaSource: CorridorPortalBlockEntity,
    private val triggeringCaster: LivingEntity?
) : CastingEnvironment(world) {

    private val triggeringPlayer: ServerPlayer? = triggeringCaster as? ServerPlayer

    override fun getCastingEntity(): LivingEntity? = triggeringCaster

    override fun getMishapEnvironment(): MishapEnvironment = NoopMishapEnvironment(world, triggeringPlayer)

    override fun mishapSprayPos(): Vec3 = Vec3.atCenterOf(origin)

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        return mediaSource.extractThresholdMedia(cost, simulate)
    }

    override fun isVecInRangeEnvironment(vec: Vec3): Boolean {
        val center = Vec3.atCenterOf(origin)
        return vec.distanceToSqr(center) <= TRIGGER_RANGE_SQ
    }

    override fun hasEditPermissionsAtEnvironment(pos: BlockPos): Boolean = false

    override fun getCastingHand(): InteractionHand = InteractionHand.MAIN_HAND

    override fun getUsableStacks(mode: StackDiscoveryMode): List<ItemStack> {
        val player = triggeringPlayer ?: return listOf()
        return getUsableStacksForPlayer(mode, getCastingHand(), player)
    }

    override fun getPrimaryStacks(): List<HeldItemInfo> {
        val player = triggeringPlayer ?: return listOf()
        return getPrimaryStacksForPlayer(getCastingHand(), player)
    }

    override fun replaceItem(
        stackOk: Predicate<ItemStack>,
        replaceWith: ItemStack,
        hand: InteractionHand?
    ): Boolean {
        val player = triggeringPlayer ?: return false
        return replaceItemForPlayer(stackOk, replaceWith, hand, player)
    }

    override fun getPigment(): FrozenPigment = FrozenPigment.DEFAULT.get()

    override fun setPigment(pigment: FrozenPigment?): FrozenPigment? = null

    override fun produceParticles(particles: ParticleSpray, colorizer: FrozenPigment) {
        // Intentionally suppressed: threshold triggers should not emit cast particle sprays.
    }

    override fun printMessage(message: Component) {
        // Threshold triggers are autonomous and have no caster to message.
    }

    private class NoopMishapEnvironment(world: ServerLevel, player: ServerPlayer?) : MishapEnvironment(world, player) {
        override fun yeetHeldItemsTowards(targetPos: Vec3) {}

        override fun dropHeldItems() {}

        override fun drown() {}

        override fun damage(healthProportion: Float) {}

        override fun removeXp(amount: Int) {}

        override fun blind(ticks: Int) {}
    }

    companion object {
        private const val TRIGGER_RANGE_BLOCKS = 12.0
        private const val TRIGGER_RANGE_SQ = TRIGGER_RANGE_BLOCKS * TRIGGER_RANGE_BLOCKS
    }
}
