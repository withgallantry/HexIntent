package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.MemoryIota
import com.bluup.manifestation.server.item.MemoryCrystalData
import com.bluup.manifestation.server.mishap.MishapMemoryIdNotOnCrystal
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.mishap.MishapRequiresMemoryCrystalInHand
import net.minecraft.server.level.ServerPlayer

/**
 * Replays a stored iota by memory id from the held Memory Crystal.
 */
object OpReplayMemory : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val memoryIota = args[0] as? MemoryIota ?: throw MishapInvalidIota.ofType(args[0], 0, "memory")

        val carrier = resolveHeldMemoryCarrier(caster, env.castingHand, memoryIota.id)
        if (carrier == null) {
            if (hasAnyMemoryCarrier(caster)) {
                throw MishapMemoryIdNotOnCrystal()
            }
            throw MishapRequiresMemoryCrystalInHand()
        }

        val stored = MemoryCrystalData.readStoredIota(carrier.stack, env.world)
            ?: throw MishapMemoryIdNotOnCrystal()

        return listOf(stored)
    }
}
