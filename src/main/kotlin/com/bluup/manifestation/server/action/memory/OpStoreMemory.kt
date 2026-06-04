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
 * Stores any iota under a memory id on the held Memory Crystal.
 */
object OpStoreMemory : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val memoryIota = args.firstOrNull { it is MemoryIota } as? MemoryIota
            ?: throw MishapInvalidIota.ofType(args[0], 1, "memory")
        val storedIota = args.firstOrNull { it !is MemoryIota }
            ?: throw MishapInvalidIota.ofType(args[0], 0, "iota")

        if (args[0] !is MemoryIota && args[1] !is MemoryIota) {
            throw MishapInvalidIota.ofType(args[0], 1, "memory")
        }

        val carrier = resolveHeldMemoryCarrier(caster, env.castingHand, memoryIota.id)
        if (carrier == null) {
            if (hasAnyMemoryCarrier(caster)) {
                throw MishapMemoryIdNotOnCrystal()
            }
            throw MishapRequiresMemoryCrystalInHand()
        }

        MemoryCrystalData.writeStoredIota(carrier.stack, storedIota)
        return listOf()
    }
}
