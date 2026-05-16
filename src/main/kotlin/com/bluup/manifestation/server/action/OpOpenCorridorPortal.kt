package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadLocation
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.PortalOwnershipStore
import com.bluup.manifestation.server.block.CorridorPortalBlock
import com.bluup.manifestation.server.block.CorridorPortalBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.iota.PresenceIntentIota
import com.bluup.manifestation.server.mishap.MishapPortalNoSpace
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

/**
 * Create a linked pair of corridor portals from two vectors.
 *
 * Stack shape on entry (top -> bottom):
 *   dust budget
 *   destination presence intent OR list of patterns (threshold trigger mode)
 *   source portal vector
 */
object OpOpenCorridorPortal : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 3) {
            throw MishapNotEnoughArgs(3, stack.size)
        }

        var scale = 1.0f

        val budgetIota = stack.removeAt(stack.lastIndex)
        val bIota = stack.removeAt(stack.lastIndex)
        val aIota = stack.removeAt(stack.lastIndex)

        val mediaBudget = if (budgetIota is DoubleIota) {
            val value = Math.round(budgetIota.double).toLong()
            if (value <= 0L) {
                throw MishapInvalidIota.ofType(budgetIota, 0, "positive dust budget")
            }
            value * MediaConstants.DUST_UNIT
        } else {
            stack.add(aIota)
            stack.add(bIota)
            stack.add(budgetIota)
            throw MishapInvalidIota.ofType(budgetIota, 0, "number (dust budget)")
        }

        val aPos = if (aIota is Vec3Iota) {
            BlockPos.containing(aIota.vec3)
        } else {
            stack.add(aIota)
            stack.add(bIota)
            throw MishapInvalidIota.ofType(aIota, 1, "vector")
        }

        // Source portal must be within ambit.
        env.assertVecInRange(Vec3.atCenterOf(aPos))

        val thresholdPayload: List<Iota>? = if (bIota is ListIota) {
            val list = bIota.list.toList()
            if (list.isEmpty()) {
                stack.add(aIota)
                stack.add(bIota)
                throw MishapInvalidIota.ofType(bIota, 0, "non-empty list of iotas")
            }

            list
        } else {
            null
        }

        val (bPos, bAxis, bDimensionId) = if (bIota is PresenceIntentIota) {
            val facing = bIota.facing
            if (facing.lengthSqr() <= 1.0e-10) {
                stack.add(aIota)
                stack.add(bIota)
                throw MishapInvalidIota.ofType(bIota, 0, "presenceIntent with non-zero facing")
            }

            val yaw = yawFromFacing(facing)
            Triple(BlockPos.containing(bIota.position), horizontalAxisForYaw(yaw), bIota.dimensionId)
        } else if (thresholdPayload != null) {
            Triple(aPos, horizontalAxisForYaw(Mth.wrapDegrees((env.castingEntity as? net.minecraft.server.level.ServerPlayer)?.yRot ?: 0f)), "")
        } else {
            stack.add(aIota)
            stack.add(bIota)
            throw MishapInvalidIota.ofType(bIota, 0, "presenceIntent or [iotas]")
        }

        val caster = env.castingEntity as? net.minecraft.server.level.ServerPlayer
            ?: throw MishapRequiresCasterWill()
        val sourceLevel = caster.serverLevel()

        val sourceYaw = Mth.wrapDegrees(caster.yRot)
        val sourceAxis = horizontalAxisForYaw(sourceYaw)

        if (env.extractMedia(mediaBudget, true) > 0) {
            throw MishapNotEnoughMedia(mediaBudget)
        }

        if (thresholdPayload != null) {
            placePortal(sourceLevel, aPos, sourceAxis)
            val threshold = sourceLevel.getBlockEntity(aPos) as? CorridorPortalBlockEntity ?: throw MishapPortalNoSpace()

            threshold.configureThresholdTrigger(
                sourceLevel,
                thresholdPayload,
                caster.uuid,
                mediaBudget,
                scale,
                sourceYaw
            )

            val image2 = image.withUsedOp().copy(stack = stack)
            return OperationResult(
                image2,
                listOf(OperatorSideEffect.ConsumeMedia(mediaBudget)),
                continuation,
                HexEvalSounds.NORMAL_EXECUTE
            )
        }

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(bDimensionId))
        val targetLevel = caster.server.getLevel(targetKey)
            ?: throw MishapBadLocation(Vec3.atCenterOf(bPos), "bad_dimension")

        if (!targetLevel.isInWorldBounds(bPos)) {
            throw MishapBadLocation(Vec3.atCenterOf(bPos), "out_of_world")
        }

        val targetYaw = yawFromFacing((bIota as PresenceIntentIota).facing)
        val ownershipStore = PortalOwnershipStore.get(caster.server)

        placePortal(sourceLevel, aPos, sourceAxis)
        placePortal(targetLevel, bPos, bAxis)

        val aPortal = sourceLevel.getBlockEntity(aPos) as? CorridorPortalBlockEntity ?: throw MishapPortalNoSpace()
        val bPortal = targetLevel.getBlockEntity(bPos) as? CorridorPortalBlockEntity ?: throw MishapPortalNoSpace()

        val previousPair = ownershipStore.get(caster.uuid)

        aPortal.linkTo(
            sourceLevel,
            bPos,
            targetLevel.dimension().location().toString(),
            caster.uuid,
            mediaBudget,
            scale,
            sourceYaw
        )
        bPortal.linkTo(
            targetLevel,
            aPos,
            sourceLevel.dimension().location().toString(),
            caster.uuid,
            mediaBudget,
            scale,
            targetYaw
        )
        ownershipStore.put(
            caster.uuid,
            PortalOwnershipStore.PortalPair(
                PortalOwnershipStore.PortalEndpoint(sourceLevel.dimension().location().toString(), aPos.immutable()),
                PortalOwnershipStore.PortalEndpoint(targetLevel.dimension().location().toString(), bPos.immutable())
            )
        )

        // Enforce one active portal pair per caster by clearing any previous pair.
        if (previousPair != null) {
            val newSource = PortalOwnershipStore.PortalEndpoint(sourceLevel.dimension().location().toString(), aPos)
            val newTarget = PortalOwnershipStore.PortalEndpoint(targetLevel.dimension().location().toString(), bPos)
            clearOwnedPortal(caster.server, previousPair.first, newSource, newTarget)
            clearOwnedPortal(caster.server, previousPair.second, newSource, newTarget)
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(
            image2,
            listOf(OperatorSideEffect.ConsumeMedia(mediaBudget)),
            continuation,
            HexEvalSounds.NORMAL_EXECUTE
        )
    }

    private fun placePortal(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, axis: net.minecraft.core.Direction.Axis) {
        val state = level.getBlockState(pos)
        if (state.block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            if (state.getValue(CorridorPortalBlock.AXIS) != axis) {
                level.setBlock(pos, state.setValue(CorridorPortalBlock.AXIS, axis), Block.UPDATE_ALL)
            }
            return
        }

        if (!state.isAir && !state.canBeReplaced()) {
            throw MishapPortalNoSpace()
        }

        val portalState = ManifestationBlocks.CORRIDOR_PORTAL_BLOCK.defaultBlockState()
            .setValue(CorridorPortalBlock.AXIS, axis)
        if (!level.setBlock(pos, portalState, Block.UPDATE_ALL)) {
            throw MishapPortalNoSpace()
        }
    }

    private fun clearOwnedPortal(
        server: net.minecraft.server.MinecraftServer,
        oldEndpoint: PortalOwnershipStore.PortalEndpoint,
        newA: PortalOwnershipStore.PortalEndpoint,
        newB: PortalOwnershipStore.PortalEndpoint
    ) {
        if (oldEndpoint == newA || oldEndpoint == newB) {
            return
        }

        val oldKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(oldEndpoint.dimensionId))
        val level = server.getLevel(oldKey) ?: return
        val oldPos = oldEndpoint.pos

        val oldState = level.getBlockState(oldPos)
        if (oldState.block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            val oldPortal = level.getBlockEntity(oldPos) as? CorridorPortalBlockEntity
            if (oldPortal != null) {
                oldPortal.beginCollapse(level)
            } else {
                level.removeBlock(oldPos, false)
            }
        }
    }

    private fun yawFromFacing(facing: Vec3): Float = Math.toDegrees(atan2(-facing.x, facing.z)).toFloat()

    private fun horizontalAxisForYaw(yaw: Float): Direction.Axis = Direction.fromYRot(yaw.toDouble()).axis
}
