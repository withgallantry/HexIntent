package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapBadLocation
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.block.IntentRelayBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.mishap.MishapIntentRelayNoSpace
import com.bluup.manifestation.server.mishap.MishapIntentRelaySignalRange
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.Vec3

/**
 * Link intent relay to target position.
 *
 * Stack shape on entry (top -> bottom):
 *   signal strength (optional; 1..15)
 *   target vector
 *   relay support vector
 */
object OpLinkIntentRelay : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        var redstoneStrength: Int? = null

        if (stack.isNotEmpty() && stack.last() is DoubleIota) {
            val signalIota = stack.removeAt(stack.lastIndex) as DoubleIota
            val rawSignal = signalIota.double
            if (rawSignal < 0.0 || rawSignal > 15.0) {
                throw MishapIntentRelaySignalRange()
            }
            val signal = Math.round(rawSignal).toInt()
            redstoneStrength = signal
        }

        if (stack.size < 2) {
            throw MishapNotEnoughArgs(2, stack.size)
        }

        val targetIota = stack.removeAt(stack.lastIndex)
        val relayIota = stack.removeAt(stack.lastIndex)

        val targetPos = if (targetIota is Vec3Iota) {
            BlockPos.containing(targetIota.vec3)
        } else {
            stack.add(relayIota)
            stack.add(targetIota)
            throw MishapInvalidIota.ofType(targetIota, 0, "vector")
        }

        val relaySupportPos = if (relayIota is Vec3Iota) {
            BlockPos.containing(relayIota.vec3)
        } else {
            stack.add(relayIota)
            stack.add(targetIota)
            throw MishapInvalidIota.ofType(relayIota, 1, "vector")
        }

        val caster = env.castingEntity as? ServerPlayer
        if (caster == null) {
            throw MishapRequiresCasterWill()
        }

        val level = caster.serverLevel()
        if (!level.isLoaded(targetPos) || level.getBlockState(targetPos).isAir) {
            throw MishapBadLocation(Vec3.atCenterOf(targetPos), "out_of_world")
        }
        if (!level.isLoaded(relaySupportPos) || level.getBlockState(relaySupportPos).isAir) {
            throw MishapBadLocation(Vec3.atCenterOf(relaySupportPos), "out_of_world")
        }

        val relayOutward = outwardTowardCaster(caster, relaySupportPos)
        val relayPlacementPos = relaySupportPos.relative(relayOutward)

        val placedRelayPos = if (level.getBlockState(relaySupportPos).block == ManifestationBlocks.INTENT_RELAY_BLOCK) {
            relaySupportPos
        } else {
            if (level.getBlockState(relayPlacementPos).block == ManifestationBlocks.INTENT_RELAY_BLOCK) {
                relayPlacementPos
            } else {
                val relayState = level.getBlockState(relayPlacementPos)
                if (!relayState.isAir && relayState.block != ManifestationBlocks.INTENT_RELAY_BLOCK) {
                    throw MishapIntentRelayNoSpace()
                }

                val wanted = relayStateFor(relayOutward, caster)
                if (!wanted.canSurvive(level, relayPlacementPos)) {
                    throw MishapIntentRelayNoSpace()
                }
                if (!level.setBlock(relayPlacementPos, wanted, Block.UPDATE_ALL)) {
                    throw MishapIntentRelayNoSpace()
                }
                relayPlacementPos
            }
        }

        val maxRange = ManifestationConfig.intentRelayMaxRangeBlocks()
        if (maxRange > 0) {
            val maxSq = maxRange.toDouble() * maxRange.toDouble()
            if (placedRelayPos.distSqr(targetPos) > maxSq) {
                throw MishapBadLocation(Vec3.atCenterOf(targetPos), "too_far")
            }
        }

        val relay = level.getBlockEntity(placedRelayPos) as? IntentRelayBlockEntity
            ?: throw MishapBadBlock(placedRelayPos, Component.translatable("block.manifestation.intent_relay"))

        relay.setTarget(level, targetPos, caster.uuid, redstoneStrength)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private fun outwardTowardCaster(caster: ServerPlayer, targetPos: BlockPos): Direction {
        val targetCenter = Vec3.atCenterOf(targetPos)
        val toCaster = caster.eyePosition.subtract(targetCenter)

        if (toCaster.lengthSqr() <= 1.0e-6) {
            return caster.direction.opposite
        }

        return Direction.getNearest(toCaster.x, toCaster.y, toCaster.z)
    }

    private fun relayStateFor(outward: Direction, caster: ServerPlayer) =
        ManifestationBlocks.INTENT_RELAY_BLOCK.defaultBlockState().let { base ->
            when (outward) {
                Direction.UP -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, caster.direction.opposite)

                Direction.DOWN -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.CEILING)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, caster.direction.opposite)

                else -> base
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.WALL)
                    .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, outward)
            }
        }
}
