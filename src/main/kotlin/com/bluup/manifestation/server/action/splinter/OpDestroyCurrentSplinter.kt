package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import com.bluup.manifestation.server.mishap.MishapRequiresSplinterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv
import com.bluup.manifestation.server.splinter.SplinterRuntime
import net.minecraft.server.level.ServerPlayer

object OpDestroyCurrentSplinter : ConstMediaAction {
    override val argc = 0

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val splinterEnv = env as? SplinterCastEnv ?: throw MishapRequiresSplinterWill()
        val caster = env.castingEntity as? ServerPlayer ?: throw MishapRequiresSplinterWill()
        SplinterRuntime.remove(caster.server, caster.uuid, splinterEnv.sourceSplinterId)
        return listOf()
    }
}