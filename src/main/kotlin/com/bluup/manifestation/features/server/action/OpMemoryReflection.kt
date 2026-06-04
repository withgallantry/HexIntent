package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import com.bluup.manifestation.server.iota.MemoryIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.mishap.MishapRequiresMemoryCrystalInHand
import net.minecraft.server.level.ServerPlayer

/**
 * Reads the held Memory Crystal id and returns a memory iota.
 */
object OpMemoryReflection : ConstMediaAction {
    override val argc = 0

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val carrier = resolveHeldMemoryCarrier(caster, env.castingHand) ?: throw MishapRequiresMemoryCrystalInHand()
        return listOf(MemoryIota(carrier.memoryId))
    }
}
