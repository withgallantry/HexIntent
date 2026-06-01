package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import com.bluup.manifestation.server.mishap.MishapRequiresSplinterWill
import com.bluup.manifestation.server.splinter.SplinterCastEnv

object OpGetSplinterLocation : ConstMediaAction {
    override val argc = 0

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val splinterEnv = env as? SplinterCastEnv ?: throw MishapRequiresSplinterWill()
        return listOf(Vec3Iota(splinterEnv.splinterOrigin))
    }
}