package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
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
object OpUnlinkIntentRelay : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.isEmpty()) {
            throw MishapNotEnoughArgs(1, stack.size)
        }

        val relayIota = stack.removeAt(stack.lastIndex)
        val relayPos = if (relayIota is Vec3Iota) {
            BlockPos.containing(relayIota.vec3)
        } else {
            stack.add(relayIota)
            throw MishapInvalidIota.ofType(relayIota, 0, "vector")
        }

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

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
