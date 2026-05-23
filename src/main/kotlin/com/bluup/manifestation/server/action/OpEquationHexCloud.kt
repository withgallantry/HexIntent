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
import com.bluup.manifestation.server.ManifestationServer
import com.bluup.manifestation.server.iota.EquationParticleIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerPlayer

/**
 * Stack shape on entry (top -> bottom):
 *   id (number)
 *   equation particle iota
 *   origin vector OR anchor entity
 *   optional offset vector when using an anchor entity
 */
object OpEquationHexCloud : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val stack = image.stack.toMutableList()
        if (stack.size < 3) {
            throw MishapNotEnoughArgs(3, stack.size)
        }

        val idIota = stack.removeAt(stack.lastIndex)
        val equationIota = stack.removeAt(stack.lastIndex)
        val anchorOrOffsetIota = stack.removeAt(stack.lastIndex)

        val cloudId = (idIota as? DoubleIota)?.let { Math.round(it.double) }
            ?: throw MishapInvalidIota.ofType(idIota, 0, "number")

        val origin = when (anchorOrOffsetIota) {
            is EntityIota -> {
                val entity = anchorOrOffsetIota.entity
                env.assertEntityInRange(entity)
                entity.position().add(0.0, entity.bbHeight.toDouble() * 0.5, 0.0)
            }

            is Vec3Iota -> {
                val maybeEntityAnchor = stack.lastOrNull() as? EntityIota
                if (maybeEntityAnchor != null) {
                    stack.removeAt(stack.lastIndex)
                    val entity = maybeEntityAnchor.entity
                    env.assertEntityInRange(entity)
                    entity.position()
                        .add(0.0, entity.bbHeight.toDouble() * 0.5, 0.0)
                        .add(anchorOrOffsetIota.vec3)
                } else {
                    anchorOrOffsetIota.vec3
                }
            }

            else -> throw MishapInvalidIota.ofType(anchorOrOffsetIota, 2, "vector or entity")
        }
        env.assertVecInRange(origin)

        val equation = equationIota as? EquationParticleIota
            ?: throw MishapInvalidIota.ofType(equationIota, 1, "equation particle")

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
            throw MishapInvalidIota.ofType(equationIota, 1, "equation particle under server evaluation budget")
        }

        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        ManifestationServer.sendEquationCloudTo(caster, origin, cloudId, equation)

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, listOf(), continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
