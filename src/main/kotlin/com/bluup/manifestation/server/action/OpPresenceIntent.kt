package com.bluup.manifestation.server.action

import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getVec3
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.misc.MediaConstants
import com.bluup.manifestation.server.iota.PresenceIntentIota
import com.bluup.manifestation.server.mishap.MishapRequiresCasterWill
import net.minecraft.server.level.ServerLevel

/**
 * Constructor operator for presence intents.
 *
 * Stack shape on entry (top -> bottom):
 *   facing vector
 *   position vector
 */
object OpPresenceIntent : ConstMediaAction {
    override val argc = 2
    private const val PRESENCE_COST_AMETHYST = 5L
    override val mediaCost: Long = PRESENCE_COST_AMETHYST * MediaConstants.CRYSTAL_UNIT

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val facing = args.getVec3(1, argc)
        if (facing.lengthSqr() <= 1.0e-10) {
            throw MishapInvalidIota.ofType(args[1], 0, "non-zero vector")
        }

        val position = args.getVec3(0, argc)

        // Presence intent target must be within caster ambit.
        env.assertVecInRange(position)

        val level = env.castingEntity?.level() as? ServerLevel
            ?: throw MishapRequiresCasterWill()

        return listOf(PresenceIntentIota(position, facing, level.dimension().location().toString()))
    }
}