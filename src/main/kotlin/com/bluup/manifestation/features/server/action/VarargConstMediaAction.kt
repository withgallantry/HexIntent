package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import net.minecraft.nbt.CompoundTag

/**
 * A constant-media action with variable arg count determined from the top of stack.
 * (Copied from Hexal)
 *
 * [stack] passed to [argc] is reversed: index 0 is the stack top.
 */
interface VarargConstMediaAction : Action {
    val mediaCost: Long
        get() = 0

    fun argc(stack: List<Iota>): Int

    fun execute(args: List<Iota>, argc: Int, userData: CompoundTag, env: CastingEnvironment): List<Iota>

    fun executeWithOpCount(
        args: List<Iota>,
        argc: Int,
        userData: CompoundTag,
        env: CastingEnvironment
    ): ConstMediaAction.CostMediaActionResult {
        val stack = this.execute(args, argc, userData, env)
        return ConstMediaAction.CostMediaActionResult(stack)
    }

    override fun operate(env: CastingEnvironment, image: CastingImage, continuation: SpellContinuation): OperationResult {
        val stack = image.stack.toMutableList()

        val argc = this.argc(stack.asReversed())
        if (argc > stack.size) {
            throw MishapNotEnoughArgs(argc, stack.size)
        }

        val args = stack.takeLast(argc)
        repeat(argc) { stack.removeAt(stack.lastIndex) }

        val userData = image.userData.copy()
        val result = this.executeWithOpCount(args, argc, userData, env)
        stack.addAll(result.resultStack)

        if (env.extractMedia(this.mediaCost, true) > 0) {
            throw MishapNotEnoughMedia(this.mediaCost)
        }

        val sideEffects = mutableListOf<OperatorSideEffect>(OperatorSideEffect.ConsumeMedia(this.mediaCost))
        val image2 = image.copy(stack = stack, opsConsumed = image.opsConsumed + result.opCount, userData = userData)
        return OperationResult(image2, sideEffects, continuation, HexEvalSounds.NORMAL_EXECUTE)
    }
}
