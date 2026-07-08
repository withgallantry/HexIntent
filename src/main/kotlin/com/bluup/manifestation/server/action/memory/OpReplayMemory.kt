package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.bluup.manifestation.server.data.ServerMemoryStorage
import com.bluup.manifestation.server.iota.MemoryIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerLevel

object OpReplayMemory : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val memoryIota = args[0] as? MemoryIota
            ?: throw MishapInvalidIota.ofType(args[0], 0, "memory")

        val world = env.world as? ServerLevel ?: throw MishapRequiresCasterWill()
        val storage = ServerMemoryStorage.get(world.server)

        val stored = storage.retrieve(memoryIota.id, world)
        return listOf(stored ?: NullIota())
    }
}