package com.bluup.manifestation.server.splinter

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

class SplinterLargeForEachRunnerTest {
    @Test
    fun collectReferencedLargeListIdsIncludesCustomExternalizedFrameReferences() {
        val inputListId = UUID.randomUUID()
        val accumulatorListId = UUID.randomUUID()
        val frame = ManifestationExternalizedForEachFrame(
            owner = UUID.randomUUID(),
            sourceSplinterId = UUID.randomUUID(),
            inputListId = inputListId,
            cursor = 0,
            totalCount = 2,
            bodyCodeTags = mutableListOf(),
            baseStackTags = mutableListOf(),
            accumulatorListId = accumulatorListId,
        )

        val referenced = SplinterLargeForEachRunner.collectReferencedLargeListIds(
            listOf(
                CompoundTag().apply {
                    putString("hexcasting:type", ManifestationExternalizedForEachFrame.TYPE_ID)
                    put("hexcasting:data", frame.serializeToNBT())
                }
            )
        )

        assertEquals(2, referenced.size)
        assertTrue(inputListId in referenced)
        assertTrue(accumulatorListId in referenced)
    }
}
