package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.DoubleIota
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.common.equation.EquationParticleConfig
import com.bluup.manifestation.common.equation.EquationParticleGenerator
import com.bluup.manifestation.Manifestation
import com.bluup.manifestation.server.ManifestationConfig
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.iota.EquationParticleIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Stack shape on entry (top -> bottom):
 *   id (number)
 *   equation particle iota
 *   origin vector OR anchor entity
 *   optional offset vector under anchor entity (entity form only)
 */
object OpEquationHexCloud : Action {
    private const val MAX_ANCHOR_OFFSET_BLOCKS = 16.0
    private const val MAX_ANCHOR_OFFSET_SQ = MAX_ANCHOR_OFFSET_BLOCKS * MAX_ANCHOR_OFFSET_BLOCKS

    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        val debugTelemetry = ManifestationConfig.splinterDebugSliceTelemetry()
        if (debugTelemetry) {
            Manifestation.LOGGER.warn(
                "Equation cloud op entered: env={}, stackSize={}, topTypes={}",
                env::class.java.simpleName,
                stack.size,
                stack.takeLast(4).map { it::class.java.simpleName }
            )
        }
        if (stack.size < 3) {
            if (debugTelemetry) {
                Manifestation.LOGGER.warn("Equation cloud op failed: not enough args (need 3, got {})", stack.size)
            }
            throw MishapNotEnoughArgs(3, stack.size)
        }

        val idIota = stack.removeAt(stack.lastIndex)
        val equationIota = stack.removeAt(stack.lastIndex)
        val anchorOrOffsetIota = stack.removeAt(stack.lastIndex)

        val cloudId = (idIota as? DoubleIota)?.let { Math.round(it.double) }
            ?: run {
                if (debugTelemetry) {
                    Manifestation.LOGGER.warn(
                        "Equation cloud op failed: id iota type mismatch (got {})",
                        idIota::class.java.simpleName
                    )
                }
                throw MishapInvalidIota.ofType(idIota, 0, "number")
            }

        val follow = when (anchorOrOffsetIota) {
            is EntityIota -> {
                val entity = anchorOrOffsetIota.entity
                env.assertEntityInRange(entity)
                // I might get rid of offset. Got this weird bug where the cloud render jumps. I suspect it's offset related but it's hard to properly debug. 
                // so more defensive codign it is!
                val offset = if (stack.isNotEmpty() && stack[stack.lastIndex] is Vec3Iota) {
                    val maybeOffset = (stack[stack.lastIndex] as Vec3Iota).vec3
                    if (maybeOffset.lengthSqr() <= MAX_ANCHOR_OFFSET_SQ) {
                        stack.removeAt(stack.lastIndex)
                        maybeOffset
                    } else {
                        Vec3.ZERO
                    }
                } else {
                    Vec3.ZERO
                }
                FollowBinding(
                    entity.position().add(0.0, entity.bbHeight.toDouble() * 0.5, 0.0).add(offset),
                    entity,
                    offset
                )
            }

            is Vec3Iota -> {
                FollowBinding(anchorOrOffsetIota.vec3, null, null)
            }

            else -> {
                if (debugTelemetry) {
                    Manifestation.LOGGER.warn(
                        "Equation cloud op failed: anchor iota type mismatch (got {})",
                        anchorOrOffsetIota::class.java.simpleName
                    )
                }
                throw MishapInvalidIota.ofType(anchorOrOffsetIota, 2, "vector or entity")
            }
        }
        val origin = follow.origin
        env.assertVecInRange(origin)

        val equation = equationIota as? EquationParticleIota
            ?: run {
                if (debugTelemetry) {
                    Manifestation.LOGGER.warn(
                        "Equation cloud op failed: equation iota type mismatch (got {})",
                        equationIota::class.java.simpleName
                    )
                }
                throw MishapInvalidIota.ofType(equationIota, 1, "equation particle")
            }

        val config = EquationParticleConfig(
            equation.xExpr,
            equation.yExpr,
            equation.zExpr,
            equation.tMin,
            equation.tMax,
            equation.uMin,
            equation.uMax,
            equation.isUseU,
            equation.pointCount,
            equation.colorMode,
            equation.fixedR,
            equation.fixedG,
            equation.fixedB,
            equation.gradientStartR,
            equation.gradientStartG,
            equation.gradientStartB,
            equation.gradientEndR,
            equation.gradientEndG,
            equation.gradientEndB,
            equation.colorExprR,
            equation.colorExprG,
            equation.colorExprB
        ).normalized()
        val evalCost = EquationParticleGenerator.estimateEvalCost(config)
        if (evalCost > ManifestationServer.MAX_EQUATION_EVAL_BUDGET_SERVER) {
            if (debugTelemetry) {
                Manifestation.LOGGER.warn(
                    "Equation cloud op failed: eval budget exceeded (cost={}, budget={})",
                    evalCost,
                    ManifestationServer.MAX_EQUATION_EVAL_BUDGET_SERVER
                )
            }
            throw MishapInvalidIota.ofType(equationIota, 1, "equation particle under server evaluation budget")
        }

        if (debugTelemetry) {
            Manifestation.LOGGER.warn(
                "Equation cloud op entered: env={}, castingEntity={}, stackSize={}, topTypes={}",
                env::class.java.simpleName,
                env.castingEntity?.javaClass?.simpleName ?: "null",
                stack.size,
                stack.takeLast(4).map { it::class.java.simpleName }
            )
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        if (debugTelemetry) {
            Manifestation.LOGGER.warn(
                "Equation cloud executed: caster={}, dim={}, id={}, origin=({}, {}, {}), points={}, usesTime={}, followEntityId={}",
                caster.name.string,
                caster.serverLevel().dimension().location(),
                cloudId,
                origin.x,
                origin.y,
                origin.z,
                equation.pointCount,
                EquationParticleGenerator.usesTime(config),
                follow.entity?.id
            )
        }
        ManifestationServer.sendEquationCloudTo(
            caster,
            origin,
            cloudId,
            equation,
            follow.entity?.id,
            follow.offset
        )

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }

    private data class FollowBinding(
        val origin: Vec3,
        val entity: Entity?,
        val offset: Vec3?
    )
}
