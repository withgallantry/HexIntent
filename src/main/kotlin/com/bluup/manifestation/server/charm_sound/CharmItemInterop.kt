package com.bluup.manifestation.server

import net.minecraft.world.item.ItemStack

/**
 * Generic helpers for detecting Hexical charm marker state on item stacks.
 */
object CharmItemInterop {
    @JvmStatic
    fun isCharmedStack(stack: ItemStack): Boolean {
        // Mirror Hexical charm marker contract without direct API linkage.
        return KotlinNbtCompat.contains(stack, "charmed")
    }
}
