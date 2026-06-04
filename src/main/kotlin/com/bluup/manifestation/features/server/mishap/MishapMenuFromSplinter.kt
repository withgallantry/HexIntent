package com.bluup.manifestation.server.mishap

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.pigment.FrozenPigment
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor

class MishapMenuFromSplinter : Mishap() {
    override fun accentColor(ctx: CastingEnvironment, errorCtx: Context): FrozenPigment =
        dyeColor(DyeColor.RED)

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
    }

    override fun errorMessage(ctx: CastingEnvironment, errorCtx: Context): Component =
        error("manifestation.menu_from_splinter")
}
