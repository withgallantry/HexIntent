package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import com.bluup.manifestation.server.KotlinNbtCompat
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import java.util.UUID

internal enum class SliceStatus {
    DONE,
    UNFINISHED,
    REMOVE
}

internal data class SliceResult(
    val status: SliceStatus,
    val didProgress: Boolean,
    val stepsRun: Int,
    val beforeOps: Long,
    val afterOps: Long,
    val beforeStackSize: Int,
    val afterStackSize: Int,
    val beforeContinuationFrameCount: Int,
    val afterContinuationFrameCount: Int,
    val completed: Boolean
)

object SplinterLargeForEachRunner {
    internal fun continuationFrameClassNames(continuation: SpellContinuation): List<String> {
        val names = mutableListOf<String>()
        var current = continuation
        while (current is SpellContinuation.NotDone) {
            names.add(current.frame::class.java.name)
            current = current.next
        }
        return names
    }

    internal fun continuationTagTypes(tags: List<CompoundTag>): List<String> {
        return tags.map { continuationTagType(it) }
    }

    internal fun collectReferencedLargeListIds(tags: List<CompoundTag>): Set<UUID> {
        val ids = mutableSetOf<UUID>()
        for (tag in tags) {
            when (continuationTagType(tag)) {
                "hexcasting:foreach" -> {
                    val frameData = getForeachDataTag(tag) ?: continue
                    collectExternalizedFieldId(frameData, "data")?.let(ids::add)
                }

                ManifestationExternalizedForEachFrame.TYPE_ID -> {
                    collectExternalizedForEachFrameListIds(tag).forEach(ids::add)
                }
            }
        }
        return ids
    }

    internal fun collectExternalizedForEachFrameListIds(frameTag: CompoundTag): Set<UUID> {
        if (continuationTagType(frameTag) != ManifestationExternalizedForEachFrame.TYPE_ID) {
            return emptySet()
        }
        if (!KotlinNbtCompat.contains(frameTag, "hexcasting:data", Tag.TAG_COMPOUND.toInt())) {
            return emptySet()
        }

        val data = KotlinNbtCompat.getCompound(frameTag, "hexcasting:data")
        val ids = mutableSetOf<UUID>()
        if (KotlinNbtCompat.hasUUID(data, ManifestationExternalizedForEachFrame.INPUT_LIST_ID_KEY)) {
            ids.add(KotlinNbtCompat.getUUID(data, ManifestationExternalizedForEachFrame.INPUT_LIST_ID_KEY))
        }
        if (KotlinNbtCompat.hasUUID(data, ManifestationExternalizedForEachFrame.ACCUMULATOR_LIST_ID_KEY)) {
            ids.add(KotlinNbtCompat.getUUID(data, ManifestationExternalizedForEachFrame.ACCUMULATOR_LIST_ID_KEY))
        }
        return ids
    }

    private fun getForeachDataTag(frameTag: CompoundTag): CompoundTag? {
        if (!KotlinNbtCompat.contains(frameTag, "hexcasting:data", Tag.TAG_COMPOUND.toInt())) {
            return null
        }
        return KotlinNbtCompat.getCompound(frameTag, "hexcasting:data")
    }

    private fun collectExternalizedFieldId(frameData: CompoundTag, fieldName: String): UUID? {
        val key = "manifestation:${fieldName}_large_list_id"
        return if (KotlinNbtCompat.hasUUID(frameData, key)) {
            KotlinNbtCompat.getUUID(frameData, key)
        } else {
            null
        }
    }

    private fun continuationTagType(tag: CompoundTag): String {
        return when {
            KotlinNbtCompat.contains(tag, "hexcasting:type", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "hexcasting:type")
            KotlinNbtCompat.contains(tag, "type", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "type")
            KotlinNbtCompat.contains(tag, "op", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "op")
            KotlinNbtCompat.contains(tag, "action", Tag.TAG_STRING.toInt()) -> KotlinNbtCompat.getString(tag, "action")
            else -> ""
        }
    }
}
