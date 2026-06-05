package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.Action
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.OperationResult
import at.petrak.hexcasting.api.casting.eval.sideeffects.EvalSound
import at.petrak.hexcasting.api.casting.eval.sideeffects.OperatorSideEffect
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughArgs
import at.petrak.hexcasting.api.casting.mishaps.MishapNotEnoughMedia
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import com.bluup.manifestation.server.CharmItemInterop
import com.bluup.manifestation.server.CharmCastSoundOverrides
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity

/**
 * Configure cast sound behavior for a Hexical charmed item.
 *
 * Stack shape on entry (top -> bottom):
 *   sound id string | text OR null
 *   optional item entity
 *
 * - string/text: set custom cast sound id (namespace:path).
 * - null: mute charm post-cast sound for this item.
 *
 * Target selection:
 * - If an item entity iota is present under the sound argument, that item is updated.
 * - Otherwise, updates a charmed item in offhand.
 */
object OpSetCharmCastSound : Action {
    override fun operate(
        env: CastingEnvironment,
        image: CastingImage,
        continuation: SpellContinuation
    ): OperationResult {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val stack = image.stack.toMutableList()
        if (stack.isEmpty()) {
            throw MishapNotEnoughArgs(1, stack.size)
        }

        val arg = stack.removeAt(stack.lastIndex)
        val explicitTarget = resolveExplicitTarget(env, stack)
        val target = explicitTarget ?: resolveHeldTarget(caster)
            ?: throw MishapInvalidIota.ofType(arg, 0, "string/text/null with a charmed item in offhand")

        val soundResult: Pair<List<OperatorSideEffect>, EvalSound> = when (arg) {
            is NullIota -> {
                if (env.extractMedia(SILENCE_CHARM_MEDIA_COST, true) > 0) {
                    throw MishapNotEnoughMedia(SILENCE_CHARM_MEDIA_COST)
                }
                CharmCastSoundOverrides.setMuted(target, true)
                listOf(OperatorSideEffect.ConsumeMedia(SILENCE_CHARM_MEDIA_COST)) to HexEvalSounds.MUTE
            }
            else -> {
                val soundId = extractStringLikeValue(arg)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw MishapInvalidIota.ofType(arg, 0, "string/text sound id (namespace:path) or null")
                val loc = ResourceLocation.tryParse(soundId)
                    ?: throw MishapInvalidIota.ofType(arg, 0, "valid sound id string/text (namespace:path)")
                if (!BuiltInRegistries.SOUND_EVENT.containsKey(loc)) {
                    throw MishapInvalidIota.ofType(arg, 0, "registered minecraft sound id")
                }
                if (env.extractMedia(SILENCE_CHARM_MEDIA_COST, true) > 0) {
                    throw MishapNotEnoughMedia(SILENCE_CHARM_MEDIA_COST)
                }
                CharmCastSoundOverrides.setSoundId(target, loc.toString())
                listOf(OperatorSideEffect.ConsumeMedia(SILENCE_CHARM_MEDIA_COST)) to HexEvalSounds.NORMAL_EXECUTE
            }
        }
        val (sideEffects, evalSound) = soundResult

        val image2 = image.withUsedOp().copy(stack = stack)
        return OperationResult(image2, sideEffects, continuation, evalSound)
    }

    private fun resolveExplicitTarget(env: CastingEnvironment, stack: MutableList<Iota>) =
        (stack.lastOrNull() as? EntityIota)?.let { entityIota ->
            val entity = entityIota.entity
            env.assertEntityInRange(entity)
            val itemEntity = entity as? ItemEntity
                ?: throw MishapInvalidIota.ofType(entityIota, 1, "item entity")
            val item = itemEntity.item
            if (item.isEmpty || !CharmItemInterop.isCharmedStack(item)) {
                throw MishapInvalidIota.ofType(entityIota, 1, "item entity containing a Hexical charmed item")
            }

            stack.removeAt(stack.lastIndex)
            item
        }

    private fun resolveHeldTarget(caster: ServerPlayer) =
        pickHeldCharmed(caster, InteractionHand.OFF_HAND)

    private fun pickHeldCharmed(caster: ServerPlayer, hand: InteractionHand) =
        caster.getItemInHand(hand).takeIf { !it.isEmpty && CharmItemInterop.isCharmedStack(it) }

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

    private const val SILENCE_CHARM_COST_DUST = 10L
    private const val SILENCE_CHARM_MEDIA_COST = SILENCE_CHARM_COST_DUST * MediaConstants.DUST_UNIT
}
