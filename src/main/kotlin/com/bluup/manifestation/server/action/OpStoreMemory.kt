package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.PatternIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.iota.MemoryIota
import com.bluup.manifestation.server.item.MemoryCrystalData
import com.bluup.manifestation.server.mishap.MishapMemoryIdNotOnCrystal
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import com.bluup.manifestation.server.mishap.MishapRequiresMemoryCrystalInHand
import net.minecraft.server.level.ServerPlayer

/**
 * Stores a list of patterns under a memory id on the held Memory Crystal.
 */
object OpStoreMemory : ConstMediaAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresCasterWill()
        val memoryIota = args.firstOrNull { it is MemoryIota } as? MemoryIota
            ?: throw MishapInvalidIota.ofType(args[0], 1, "memory")
        val patternList = args.firstOrNull { it is ListIota } as? ListIota
            ?: throw MishapInvalidIota.ofType(args[0], 0, "list of patterns")

        if (args[0] !is MemoryIota && args[1] !is MemoryIota) {
            throw MishapInvalidIota.ofType(args[0], 1, "memory")
        }

        if (args[0] !is ListIota && args[1] !is ListIota) {
            throw MishapInvalidIota.ofType(args[0], 0, "list of patterns")
        }

        val carrier = resolveHeldMemoryCarrier(caster, env.castingHand, memoryIota.id)
        if (carrier == null) {
            if (hasAnyMemoryCarrier(caster)) {
                throw MishapMemoryIdNotOnCrystal()
            }
            throw MishapRequiresMemoryCrystalInHand()
        }

        val entries = patternList.list.toList()
        for (entry in entries) {
            if (entry !is PatternIota) {
                throw MishapInvalidIota.ofType(entry, 0, "pattern")
            }
        }

        MemoryCrystalData.writePatterns(carrier.stack, patternList)
        return listOf()
    }
}
