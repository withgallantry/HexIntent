package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadLocation
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import at.petrak.hexcasting.common.items.pigment.ItemDyePigment
import com.bluup.manifestation.server.PortalOwnershipStore
import com.bluup.manifestation.server.block.CorridorPortalBlock
import com.bluup.manifestation.server.block.CorridorPortalBlockEntity
import com.bluup.manifestation.server.block.ManifestationBlocks
import com.bluup.manifestation.server.block.PermanentThresholdFrame
import com.bluup.manifestation.server.block.PermanentThresholdFrames
import com.bluup.manifestation.server.iota.PresenceIntentIota
import com.bluup.manifestation.server.mishap.MishapPortalNoSpace
import com.bluup.manifestation.server.mishap.MishapPermanentThresholdFrameInvalid
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Block
import net.minecraft.world.item.DyeColor
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2

/**
 * Create a linked pair of corridor portals from two vectors.
 *
 * Stack shape on entry (top -> bottom):
 *   optional pigment color iota
 *   optional label text/string iota
 *   dust budget
 *   destination presence intent
 *   source portal vector
 */
object OpOpenCorridorPortal : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        var pigmentTintOverrideRgb: Int? = null
        var portalLabel: String? = null

        if (stack.size >= 5) {
            val maybePigment = stack.last()
            pigmentTintOverrideRgb = extractPigmentTintRgb(maybePigment)
            if (pigmentTintOverrideRgb != null) {
                stack.removeAt(stack.lastIndex)
            } else {
                throw MishapInvalidIota.ofType(maybePigment, 0, "pigment color iota (optional 5th input)")
            }
        }

        if (stack.size >= 4) {
            val maybeLabel = stack.last()
            portalLabel = extractPortalLabel(maybeLabel)
            if (portalLabel != null) {
                stack.removeAt(stack.lastIndex)
            } else if (maybeLabel !is DoubleIota) {
                throw MishapInvalidIota.ofType(maybeLabel, 0, "text/string iota (optional label) or number (dust budget)")
            }
        }

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

        val (bPos, bAxis, bDimensionId) = if (bIota is PresenceIntentIota) {
            val facing = bIota.facing
            if (facing.lengthSqr() <= 1.0e-10) {
                stack.add(aIota)
                stack.add(bIota)
                throw MishapInvalidIota.ofType(bIota, 0, "presenceIntent with non-zero facing")
            }

            val yaw = yawFromFacing(facing)
            Triple(BlockPos.containing(bIota.position), horizontalAxisForYaw(yaw), bIota.dimensionId)
        } else {
            stack.add(aIota)
            stack.add(bIota)
            throw MishapInvalidIota.ofType(bIota, 0, "presenceIntent")
        }

        val caster = env.castingEntity as? net.minecraft.server.level.ServerPlayer
            ?: throw MishapRequiresCasterWill()
        val sourceLevel = caster.serverLevel()

        val sourceYaw = Mth.wrapDegrees(caster.yRot)
        val sourceAxis = horizontalAxisForYaw(sourceYaw)
        val portalTint = resolvePortalTint(env, pigmentTintOverrideRgb)

        val targetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(bDimensionId))
        val targetLevel = caster.server.getLevel(targetKey)
            ?: throw MishapBadLocation(Vec3.atCenterOf(bPos), "bad_dimension")

        if (!targetLevel.isInWorldBounds(bPos)) {
            throw MishapBadLocation(Vec3.atCenterOf(bPos), "out_of_world")
        }

        val targetYaw = yawFromFacing(bIota.facing)
        val ownershipStore = PortalOwnershipStore.get(caster.server)

        val sourceFrame = PermanentThresholdFrames.findAt(sourceLevel, aPos)
        val targetFrame = PermanentThresholdFrames.findAt(targetLevel, bPos)
        val permanentFrameFlow = sourceFrame != null || targetFrame != null

        if (permanentFrameFlow && (sourceFrame == null || targetFrame == null)) {
            throw MishapPermanentThresholdFrameInvalid()
        }

        val sourcePortalPos = sourceFrame?.anchorPos() ?: aPos
        val targetPortalPos = targetFrame?.anchorPos() ?: bPos
        val sourcePortalAxis = sourceFrame?.axis ?: sourceAxis
        val targetPortalAxis = targetFrame?.axis ?: bAxis
        val sourceRenderYaw = if (sourceFrame != null) snapYawToAxis(sourceYaw, sourcePortalAxis) else sourceYaw
        val targetRenderYaw = if (targetFrame != null) snapYawToAxis(targetYaw, targetPortalAxis) else targetYaw
        val openMediaCost = if (permanentFrameFlow) {
            PERMANENT_THRESHOLD_OPEN_MEDIA_COST
        } else {
            mediaBudget
        }

        if (env.extractMedia(openMediaCost, true) > 0) {
            throw MishapNotEnoughMedia(openMediaCost)
        }

        if (permanentFrameFlow) {
            sourceFrame?.let { clearPermanentFrameControllers(sourceLevel, it) }
            if (sourceLevel != targetLevel || sourcePortalPos != targetPortalPos) {
                targetFrame?.let { clearPermanentFrameControllers(targetLevel, it) }
            }
        }

        val sourcePlacement = preparePortalPlacement(sourceLevel, sourcePortalPos, sourcePortalAxis)
        val targetPlacement = preparePortalPlacement(targetLevel, targetPortalPos, targetPortalAxis)

        applyPortalPlacement(sourceLevel, sourcePlacement)
        try {
            applyPortalPlacement(targetLevel, targetPlacement)

            val aPortal = sourceLevel.getBlockEntity(sourcePortalPos) as? CorridorPortalBlockEntity
                ?: throw MishapPortalNoSpace()
            val bPortal = targetLevel.getBlockEntity(targetPortalPos) as? CorridorPortalBlockEntity
                ?: throw MishapPortalNoSpace()

            val previousPair = ownershipStore.get(caster.uuid)

            aPortal.linkTo(
                sourceLevel,
                targetPortalPos,
                targetLevel.dimension().location().toString(),
                caster.uuid,
                mediaBudget,
                scale,
                sourceRenderYaw,
                permanentFrameFlow,
                sourceFrame,
                targetFrame
            )
            bPortal.linkTo(
                targetLevel,
                sourcePortalPos,
                sourceLevel.dimension().location().toString(),
                caster.uuid,
                mediaBudget,
                scale,
                targetRenderYaw,
                permanentFrameFlow,
                targetFrame,
                sourceFrame
            )
            aPortal.applyPortalAccentTint(portalTint.dyeColor, portalTint.resolvedTintRgb, portalTint.colorizer)
            bPortal.applyPortalAccentTint(portalTint.dyeColor, portalTint.resolvedTintRgb, portalTint.colorizer)
            aPortal.setPortalLabel(portalLabel)
            ownershipStore.put(
                caster.uuid,
                PortalOwnershipStore.PortalPair(
                    PortalOwnershipStore.PortalEndpoint(sourceLevel.dimension().location().toString(), sourcePortalPos.immutable()),
                    PortalOwnershipStore.PortalEndpoint(targetLevel.dimension().location().toString(), targetPortalPos.immutable())
                )
            )

            // Enforce one active portal pair per caster by clearing any previous pair.
            if (previousPair != null) {
                val newSource = PortalOwnershipStore.PortalEndpoint(sourceLevel.dimension().location().toString(), sourcePortalPos)
                val newTarget = PortalOwnershipStore.PortalEndpoint(targetLevel.dimension().location().toString(), targetPortalPos)
                clearOwnedPortal(caster.server, previousPair.first, newSource, newTarget)
                clearOwnedPortal(caster.server, previousPair.second, newSource, newTarget)
            }
        } catch (t: Throwable) {
            rollbackPortalPlacement(sourceLevel, sourcePlacement)
            rollbackPortalPlacement(targetLevel, targetPlacement)
            throw t
        }

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(
            image2,
            listOf(OperatorSideEffect.ConsumeMedia(openMediaCost)),
            continuation,
            HexEvalSounds.NORMAL_EXECUTE
        )
    }

    private data class PortalPlacement(
        val pos: BlockPos,
        val axis: net.minecraft.core.Direction.Axis,
        val hasExistingPortal: Boolean,
        val previousAxis: net.minecraft.core.Direction.Axis?
    )

    private fun preparePortalPlacement(
        level: net.minecraft.server.level.ServerLevel,
        pos: BlockPos,
        axis: net.minecraft.core.Direction.Axis
    ): PortalPlacement {
        val state = level.getBlockState(pos)
        if (state.block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return PortalPlacement(pos, axis, true, state.getValue(CorridorPortalBlock.AXIS))
        }

        if (!state.isAir && !state.canBeReplaced()) {
            throw MishapPortalNoSpace()
        }

        return PortalPlacement(pos, axis, false, null)
    }

    private fun applyPortalPlacement(level: net.minecraft.server.level.ServerLevel, placement: PortalPlacement) {
        val pos = placement.pos
        if (placement.hasExistingPortal) {
            val state = level.getBlockState(pos)
            if (state.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                throw MishapPortalNoSpace()
            }
            if (state.getValue(CorridorPortalBlock.AXIS) != placement.axis) {
                level.setBlock(pos, state.setValue(CorridorPortalBlock.AXIS, placement.axis), Block.UPDATE_ALL)
            }
            return
        }

        val portalState = ManifestationBlocks.CORRIDOR_PORTAL_BLOCK.defaultBlockState()
            .setValue(CorridorPortalBlock.AXIS, placement.axis)
        if (!level.setBlock(pos, portalState, Block.UPDATE_ALL)) {
            throw MishapPortalNoSpace()
        }
    }

    private fun rollbackPortalPlacement(level: net.minecraft.server.level.ServerLevel, placement: PortalPlacement) {
        val currentState = level.getBlockState(placement.pos)
        if (currentState.block != ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
            return
        }

        if (placement.hasExistingPortal) {
            val previousAxis = placement.previousAxis ?: return
            if (currentState.getValue(CorridorPortalBlock.AXIS) != previousAxis) {
                level.setBlock(placement.pos, currentState.setValue(CorridorPortalBlock.AXIS, previousAxis), Block.UPDATE_ALL)
            }
            return
        }

        level.removeBlock(placement.pos, false)
    }

    private fun clearPermanentFrameControllers(
        level: net.minecraft.server.level.ServerLevel,
        frame: PermanentThresholdFrame
    ) {
        for (horizontal in 0..3) {
            for (vertical in 0..4) {
                val pos = frame.framePos(horizontal, vertical)
                val state = level.getBlockState(pos)
                if (state.block == ManifestationBlocks.CORRIDOR_PORTAL_BLOCK) {
                    level.removeBlock(pos, false)
                }
            }
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

    private fun snapYawToAxis(preferredYaw: Float, axis: Direction.Axis): Float {
        val positiveYaw = when (axis) {
            Direction.Axis.X -> -90.0f
            Direction.Axis.Z -> 0.0f
            else -> Mth.wrapDegrees(preferredYaw)
        }
        val negativeYaw = Mth.wrapDegrees(positiveYaw + 180.0f)
        val normalizedPreferred = Mth.wrapDegrees(preferredYaw)
        return if (angularDistance(normalizedPreferred, positiveYaw) <= angularDistance(normalizedPreferred, negativeYaw)) {
            positiveYaw
        } else {
            negativeYaw
        }
    }

    private fun angularDistance(a: Float, b: Float): Float = kotlin.math.abs(Mth.wrapDegrees(a - b))

    private fun extractPortalLabel(iota: Iota): String? {
        val className = iota.javaClass.name.lowercase()
        if (!className.contains("stringiota") && !className.contains("textiota")) {
            return null
        }

        val candidate = extractStringLikeValue(iota) ?: return null
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        return trimmed.take(MAX_LABEL_LENGTH)
    }

    private fun extractStringLikeValue(iota: Iota): String? {
        val methodNames = listOf("getString", "string", "getText", "text", "getValue", "value")
        for (methodName in methodNames) {
            try {
                val method = iota.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                } ?: continue
                val raw = method.invoke(iota) ?: continue
                when (raw) {
                    is String -> return raw
                    is net.minecraft.network.chat.Component -> return raw.string
                }
            } catch (_: Throwable) {
                // Try the next accessor.
            }
        }

        return null
    }

    private fun extractPigmentTintRgb(iota: Iota): Int? {
        val className = iota.javaClass.name.lowercase()
        if (!className.contains("pigment")) {
            return null
        }

        val directInt = tryInvokeMethods(iota, "getColor", "color", "getRgb", "rgb")
        if (directInt is Number) {
            return directInt.toInt() and 0xFFFFFF
        }

        val pigmentCarrier = tryInvokeMethods(iota, "getPigment", "pigment", "getValue", "value")
        if (pigmentCarrier != null) {
            val provider = tryInvokeMethods(pigmentCarrier, "getColorProvider", "colorProvider")
            if (provider != null) {
                val colorMethod = provider.javaClass.methods.firstOrNull { it.name == "getColor" }
                if (colorMethod != null) {
                    try {
                        val args = when (colorMethod.parameterCount) {
                            2 -> arrayOf<Any>(0.0f, Vec3.ZERO)
                            1 -> arrayOf<Any>(0.0f)
                            else -> emptyArray()
                        }
                        if (args.isNotEmpty()) {
                            val raw = colorMethod.invoke(provider, *args)
                            if (raw is Number) {
                                return raw.toInt() and 0xFFFFFF
                            }
                        }
                    } catch (_: Throwable) {
                        // Fall through to unsupported format.
                    }
                }
            }
        }

        return null
    }

    private fun tryInvokeMethods(target: Any, vararg names: String): Any? {
        for (name in names) {
            try {
                val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 } ?: continue
                return method.invoke(target)
            } catch (_: Throwable) {
                // Try next accessor.
            }
        }
        return null
    }

    private data class PortalTintResolution(
        val dyeColor: DyeColor?,
        val resolvedTintRgb: Int,
        val colorizer: FrozenPigment?
    )

    private const val MAX_LABEL_LENGTH = 64
    private const val PERMANENT_THRESHOLD_OPEN_MEDIA_COST = 35L * MediaConstants.CRYSTAL_UNIT

    private fun resolvePortalTint(env: CastingEnvironment, explicitTintOverrideRgb: Int?): PortalTintResolution {
        if (explicitTintOverrideRgb != null) {
            return PortalTintResolution(null, explicitTintOverrideRgb and 0xFFFFFF, null)
        }

        val activePigment = env.pigment
        val activeItem = activePigment.item.item
        val explicitDye = (activeItem as? ItemDyePigment)?.dyeColor

        val activeRgb = activePigment
            .getColorProvider()
            .getColor(0.0f, Vec3.ZERO) and 0xFFFFFF

        return PortalTintResolution(explicitDye, activeRgb, activePigment)
    }
}
