package com.bluup.manifestation.server.action

import com.bluup.manifestation.server.item.ManifestationItems
import com.bluup.manifestation.server.item.MemoryCrystalData
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

internal data class HeldMemoryCarrier(
    val hand: InteractionHand,
    val stack: ItemStack,
    val memoryId: String
)

internal fun hasAnyMemoryCarrier(caster: ServerPlayer): Boolean {
    return resolveHeldMemoryCarrier(caster, InteractionHand.MAIN_HAND) != null
}

internal fun resolveHeldMemoryCarrier(caster: ServerPlayer, preferred: InteractionHand): HeldMemoryCarrier? {
    for (hand in handPriority(preferred)) {
        val stack = caster.getItemInHand(hand)
        val id = resolveMemoryId(stack) ?: continue
        return HeldMemoryCarrier(hand, stack, id)
    }

    return null
}

internal fun resolveHeldMemoryCarrier(caster: ServerPlayer, preferred: InteractionHand, memoryId: String): HeldMemoryCarrier? {
    for (hand in handPriority(preferred)) {
        val stack = caster.getItemInHand(hand)
        val id = resolveMemoryId(stack) ?: continue
        if (id == memoryId) {
            return HeldMemoryCarrier(hand, stack, id)
        }
    }

    return null
}

private fun resolveMemoryId(stack: ItemStack): String? {
    if (!MemoryCrystalData.isMemoryCarrier(stack)) {
        return null
    }

    return if (stack.`is`(ManifestationItems.MEMORY_CRYSTAL)) {
        MemoryCrystalData.ensureMemoryId(stack)
    } else {
        MemoryCrystalData.getMemoryId(stack)
    }
}

private fun handPriority(preferred: InteractionHand): Array<InteractionHand> {
    return arrayOf(InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND)
}
