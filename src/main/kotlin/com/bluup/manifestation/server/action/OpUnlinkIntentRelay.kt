package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.block.IntentRelayBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * Clear a relay block's linked target.
 *
 * Stack shape on entry (top -> bottom):
 *   relay vector
 */
object OpUnlinkIntentRelay : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val relayIota = args[0]
        val relayPos = (relayIota as? Vec3Iota)?.let { BlockPos.containing(it.vec3) }
            ?: throw MishapInvalidIota.ofType(relayIota, 0, "vector")

        val world = env.world
        val resolvedRelayPos = IntentRelayPosResolver.resolve(world, relayPos)
            ?: throw MishapBadBlock(relayPos, Component.translatable("block.manifestation.intent_relay"))

        val relayState = world.getBlockState(resolvedRelayPos)
        if (relayState.block != ManifestationBlocks.INTENT_RELAY_BLOCK) {
            throw MishapBadBlock(relayPos, Component.translatable("block.manifestation.intent_relay"))
        }

        val relay = world.getBlockEntity(resolvedRelayPos) as? IntentRelayBlockEntity
            ?: throw MishapBadBlock(relayPos, Component.translatable("block.manifestation.intent_relay"))

        relay.clearTarget()
        return listOf()
    }
}
