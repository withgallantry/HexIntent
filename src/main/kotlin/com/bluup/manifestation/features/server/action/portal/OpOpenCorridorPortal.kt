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
import java.util.UUID

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

        if (canConsumeOptionalPigment(stack)) {
            val maybePigment = stack.last()
            pigmentTintOverrideRgb = extractPigmentTintRgb(maybePigment)
            if (pigmentTintOverrideRgb == null) {
                throw MishapInvalidIota.ofType(maybePigment, 0, "pigment color iota (optional 5th input)")
            }
            stack.removeAt(stack.lastIndex)
        }

        if (canConsumeOptionalLabel(stack)) {
            val maybeLabel = stack.last()
            portalLabel = extractPortalLabel(maybeLabel)
            if (portalLabel == null) {
                throw MishapInvalidIota.ofType(maybeLabel, 0, "text/string iota (optional label) or number (dust budget)")
            }
            stack.removeAt(stack.lastIndex)
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

        val previousPair = if (permanentFrameFlow) {
            null
        } else {
            ownershipStore.get(caster.uuid)
        }
        val openRequest = DeferredPortalOpenRequest(
            sourceDimensionId = sourceLevel.dimension().location().toString(),
            sourcePos = sourcePortalPos.immutable(),
            sourceAxis = sourcePortalAxis,
            sourceRenderYaw = sourceRenderYaw,
            targetDimensionId = targetLevel.dimension().location().toString(),
            targetPos = targetPortalPos.immutable(),
            targetAxis = targetPortalAxis,
            targetRenderYaw = targetRenderYaw,
            ownerUuid = caster.uuid,
            mediaBudget = mediaBudget,
            scale = scale,
            permanentFrameFlow = permanentFrameFlow,
            sourceFrame = sourceFrame,
            targetFrame = targetFrame,
            portalTintResolvedRgb = portalTint.resolvedTintRgb,
            portalTintColorizer = portalTint.colorizer,
            portalLabel = portalLabel,
            previousOwnedPair = previousPair
        )

        val replacementPortals = linkedMapOf<String, ActivePermanentReplacementPortal>()
        collectPermanentReplacementPortal(replacementPortals, sourceLevel, sourcePortalPos)
        if (sourceLevel != targetLevel || sourcePortalPos != targetPortalPos) {
            collectPermanentReplacementPortal(replacementPortals, targetLevel, targetPortalPos)
        }
        previousPair?.let {
            collectPermanentReplacementPortal(caster.server, replacementPortals, it.first)
            collectPermanentReplacementPortal(caster.server, replacementPortals, it.second)
        }

        val collapsingPortals = replacementPortals.values.toList()
        if (collapsingPortals.isNotEmpty()) {
            val driver = collapsingPortals.first()
            driver.portal.beginReplacementCollapse(driver.level, openRequest)
            for (portal in collapsingPortals.drop(1)) {
                portal.portal.beginReplacementCollapse(portal.level)
            }
        } else {
            completeDeferredOpen(caster.server, openRequest)
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

    data class DeferredPortalOpenRequest(
        val sourceDimensionId: String,
        val sourcePos: BlockPos,
        val sourceAxis: net.minecraft.core.Direction.Axis,
        val sourceRenderYaw: Float,
        val targetDimensionId: String,
        val targetPos: BlockPos,
        val targetAxis: net.minecraft.core.Direction.Axis,
        val targetRenderYaw: Float,
        val ownerUuid: UUID,
        val mediaBudget: Long,
        val scale: Float,
        val permanentFrameFlow: Boolean,
        val sourceFrame: PermanentThresholdFrame?,
        val targetFrame: PermanentThresholdFrame?,
        val portalTintResolvedRgb: Int,
        val portalTintColorizer: FrozenPigment?,
        val portalLabel: String?,
        val previousOwnedPair: PortalOwnershipStore.PortalPair?
    )

    private data class ActivePermanentReplacementPortal(
        val level: net.minecraft.server.level.ServerLevel,
        val portal: CorridorPortalBlockEntity
    )

    internal fun completeDeferredOpen(server: net.minecraft.server.MinecraftServer, request: DeferredPortalOpenRequest) {
        val sourceLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation(request.sourceDimensionId)))
            ?: throw MishapPortalNoSpace()
        val targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation(request.targetDimensionId)))
            ?: throw MishapPortalNoSpace()

        val sourcePlacement = preparePortalPlacement(sourceLevel, request.sourcePos, request.sourceAxis)
        val targetPlacement = preparePortalPlacement(targetLevel, request.targetPos, request.targetAxis)

        applyPortalPlacement(sourceLevel, sourcePlacement)
        try {
            applyPortalPlacement(targetLevel, targetPlacement)

            val aPortal = sourceLevel.getBlockEntity(request.sourcePos) as? CorridorPortalBlockEntity
                ?: throw MishapPortalNoSpace()
            val bPortal = targetLevel.getBlockEntity(request.targetPos) as? CorridorPortalBlockEntity
                ?: throw MishapPortalNoSpace()

            aPortal.linkTo(
                sourceLevel,
                request.targetPos,
                request.targetDimensionId,
                request.ownerUuid,
                request.mediaBudget,
                request.scale,
                request.sourceRenderYaw,
                request.permanentFrameFlow,
                request.sourceFrame,
                request.targetFrame
            )
            bPortal.linkTo(
                targetLevel,
                request.sourcePos,
                request.sourceDimensionId,
                request.ownerUuid,
                request.mediaBudget,
                request.scale,
                request.targetRenderYaw,
                request.permanentFrameFlow,
                request.targetFrame,
                request.sourceFrame
            )
            aPortal.applyPortalAccentTint(null, request.portalTintResolvedRgb, request.portalTintColorizer)
            bPortal.applyPortalAccentTint(null, request.portalTintResolvedRgb, request.portalTintColorizer)
            aPortal.setPortalLabel(request.portalLabel)

            if (!request.permanentFrameFlow) {
                val ownershipStore = PortalOwnershipStore.get(server)
                val newSource = PortalOwnershipStore.PortalEndpoint(request.sourceDimensionId, request.sourcePos.immutable())
                val newTarget = PortalOwnershipStore.PortalEndpoint(request.targetDimensionId, request.targetPos.immutable())
                ownershipStore.put(request.ownerUuid, PortalOwnershipStore.PortalPair(newSource, newTarget))

                val previousPair = request.previousOwnedPair
                if (previousPair != null) {
                    clearOwnedPortal(server, previousPair.first, newSource, newTarget)
                    clearOwnedPortal(server, previousPair.second, newSource, newTarget)
                }
            }
        } catch (t: Throwable) {
            rollbackPortalPlacement(sourceLevel, sourcePlacement)
            rollbackPortalPlacement(targetLevel, targetPlacement)
            throw t
        }
    }

    private fun collectPermanentReplacementPortal(
        replacements: MutableMap<String, ActivePermanentReplacementPortal>,
        level: net.minecraft.server.level.ServerLevel,
        pos: BlockPos
    ) {
        val portal = level.getBlockEntity(pos) as? CorridorPortalBlockEntity ?: return
        if (!portal.isPermanentFrameMode()) {
            return
        }

        val targetPos = portal.getRenderTargetPos() ?: return
        val targetDimensionId = portal.getRenderTargetDimensionId() ?: return
        val ownDimensionId = level.dimension().location().toString()
        val pairKey = pairKey(ownDimensionId, pos, targetDimensionId, targetPos)
        replacements.putIfAbsent(pairKey, ActivePermanentReplacementPortal(level, portal))
    }

    private fun collectPermanentReplacementPortal(
        server: net.minecraft.server.MinecraftServer,
        replacements: MutableMap<String, ActivePermanentReplacementPortal>,
        endpoint: PortalOwnershipStore.PortalEndpoint
    ) {
        val levelKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation(endpoint.dimensionId))
        val level = server.getLevel(levelKey) ?: return
        collectPermanentReplacementPortal(replacements, level, endpoint.pos)
    }

    private fun pairKey(
        firstDimensionId: String,
        firstPos: BlockPos,
        secondDimensionId: String,
        secondPos: BlockPos
    ): String {
        val a = firstDimensionId + ":" + firstPos.asLong()
        val b = secondDimensionId + ":" + secondPos.asLong()
        return if (a <= b) {
            a + "|" + b
        } else {
            b + "|" + a
        }
    }

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

    private fun hasCoreSignature(stack: List<Iota>, optionCountAboveCore: Int): Boolean {
        val budgetIndex = stack.lastIndex - optionCountAboveCore
        val presenceIndex = budgetIndex - 1
        val sourceIndex = budgetIndex - 2
        if (sourceIndex < 0) {
            return false
        }

        return stack[budgetIndex] is DoubleIota
            && stack[presenceIndex] is PresenceIntentIota
            && stack[sourceIndex] is Vec3Iota
    }

    private fun isStringLikeType(iota: Iota): Boolean {
        val className = iota.javaClass.name.lowercase()
        return className.contains("stringiota") || className.contains("textiota")
    }

    private fun canConsumeOptionalLabel(stack: List<Iota>): Boolean {
        if (stack.isEmpty()) {
            return false
        }
        val top = stack.last()
        return isStringLikeType(top) && hasCoreSignature(stack, 1)
    }

    private fun canConsumeOptionalPigment(stack: List<Iota>): Boolean {
        if (stack.isEmpty()) {
            return false
        }
        val top = stack.last()
        if (extractPigmentTintRgb(top) == null) {
            return false
        }

        // pigment + core
        if (hasCoreSignature(stack, 1)) {
            return true
        }

        // pigment + label + core
        return stack.size >= 5 && isStringLikeType(stack[stack.lastIndex - 1]) && hasCoreSignature(stack, 2)
    }

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
