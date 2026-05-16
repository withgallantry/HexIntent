package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.env.CircleCastEnv
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv
import com.bluup.manifestation.common.menu.MenuPayload

object MenuDispatchSourceResolver {
    private const val HEXICAL_CHARM_ENV_CLASS = "miyucomics.hexical.features.charms.CharmCastEnv"

    fun fromEnvironment(env: CastingEnvironment): MenuPayload.DispatchSource {
        return when {
            env.javaClass.name == HEXICAL_CHARM_ENV_CLASS -> MenuPayload.DispatchSource.HEXICAL_CHARM
            env is CircleCastEnv -> MenuPayload.DispatchSource.CIRCLE
            env is StaffCastEnv -> MenuPayload.DispatchSource.STAFF
            else -> MenuPayload.DispatchSource.PACKAGED_ITEM
        }
    }
}