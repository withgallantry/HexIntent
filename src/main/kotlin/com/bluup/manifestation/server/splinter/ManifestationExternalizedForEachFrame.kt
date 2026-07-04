package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.api.casting.eval.CastResult
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.eval.vm.ContinuationFrame
import at.petrak.hexcasting.api.casting.eval.vm.CastingVM
import at.petrak.hexcasting.api.casting.eval.vm.FrameEvaluate
import at.petrak.hexcasting.api.casting.eval.vm.SpellContinuation
import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.IotaType
import at.petrak.hexcasting.api.casting.iota.ListIota
import com.bluup.manifestation.Manifestation
import at.petrak.hexcasting.common.lib.hex.HexEvalSounds
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import java.util.UUID

data class ManifestationExternalizedForEachFrame(
    val owner: UUID,
    val sourceSplinterId: UUID,
    val inputListId: UUID,
    var cursor: Int,
    val totalCount: Int,
    val bodyCodeTags: MutableList<CompoundTag>,
    var baseStackTags: MutableList<CompoundTag>?,
    var accumulatorListId: UUID
) : ContinuationFrame {
    override fun evaluate(
        continuation: SpellContinuation,
        level: ServerLevel,
        harness: CastingVM
    ): CastResult {
        val context = ManifestationSplinterFrameContext.requireCurrent()
        if (context.splinterId != sourceSplinterId || context.ownerId != owner) {
            throw IllegalStateException("manifestation_externalized_foreach_frame_context_mismatch")
        }

        val store = context.state
        val inputEntry = store.getLargeListEntry(inputListId)
            ?: throw IllegalStateException("manifestation_externalized_foreach_frame_missing_input_list")
        if (inputEntry.totalCount != totalCount) {
            throw IllegalStateException("manifestation_externalized_foreach_frame_input_count_mismatch")
        }
        if (cursor < 0 || cursor > totalCount) {
            throw IllegalStateException("manifestation_externalized_foreach_frame_cursor_invalid")
        }
        val bodyCode = deserializeTagListToIotasOrNull(bodyCodeTags, level, context.ownerPlayer)
            ?: throw IllegalStateException("manifestation_externalized_foreach_frame_body_decode_failed")

        val firstStep = baseStackTags == null
        val baseStack = if (firstStep) {
            harness.image.stack.toList()
        } else {
            deserializeTagListToIotasOrNull(baseStackTags!!, level, context.ownerPlayer)
                ?: throw IllegalStateException("manifestation_externalized_foreach_frame_base_stack_decode_failed")
        }

        if (firstStep) {
            baseStackTags = serializeIotasOrNull(baseStack, context.ownerPlayer)
                ?: throw IllegalStateException("manifestation_externalized_foreach_frame_base_stack_serialize_failed")
        } else {
            val completedBodyStackTags = serializeIotasOrNull(harness.image.stack.toList(), context.ownerPlayer)
                ?: throw IllegalStateException("manifestation_externalized_foreach_frame_body_stack_serialize_failed")
            if (!store.appendLargeListItems(accumulatorListId, completedBodyStackTags, level.gameTime)) {
                throw IllegalStateException("manifestation_externalized_foreach_frame_accumulator_append_failed")
            }
        }

        return if (cursor < totalCount) {
            val datum = readLargeListItemAsIotaOrNull(
                store = store,
                listId = inputListId,
                index = cursor,
                level = level,
                owner = context.ownerPlayer,
                accessGameTime = level.gameTime
            ) ?: throw IllegalStateException("manifestation_externalized_foreach_frame_item_load_failed")

            val nextFrame = copy(
                cursor = cursor + 1,
                baseStackTags = copyCompoundTagListOrNull(baseStackTags)
            )
            val newContinuation = continuation
                .pushFrame(nextFrame)
                .pushFrame(FrameEvaluate(SpellList.LList(bodyCode), true))

            val nextStack = baseStack.toMutableList()
            nextStack.add(datum)

            if (context.debugTelemetry) {
                Manifestation.LOGGER.info(
                    "Manifestation externalized foreach step: splinter={}, cursor={}, next={}, total={}, firstStep={}, baseStack={}, accumulatorListId={}",
                    sourceSplinterId,
                    cursor,
                    cursor + 1,
                    totalCount,
                    firstStep,
                    baseStack.size,
                    accumulatorListId
                )
            }

            CastResult(
                ListIota(SpellList.LList(bodyCode)),
                newContinuation,
                harness.image.withUsedOp().withResetEscape().copy(stack = nextStack),
                emptyList(),
                ResolvedPatternType.EVALUATED,
                HexEvalSounds.THOTH
            )
        } else {
            val accumulatorEntry = store.getLargeListEntry(accumulatorListId)
                ?: throw IllegalStateException("manifestation_externalized_foreach_frame_missing_accumulator_list")
            if (accumulatorEntry.totalCount > context.safeInlineCap && continuation is SpellContinuation.NotDone) {
                Manifestation.LOGGER.warn(
                    "Manifestation externalized foreach produced a large result: splinter={}, total={}, resultSize={}, safeInlineCap={}",
                    sourceSplinterId,
                    totalCount,
                    accumulatorEntry.totalCount,
                    context.safeInlineCap
                )
            }

            val accumulator = readLargeListRangeAsIotasOrNull(
                store = store,
                listId = accumulatorListId,
                start = 0,
                count = accumulatorEntry.totalCount,
                level = level,
                owner = context.ownerPlayer,
                accessGameTime = level.gameTime
            ) ?: throw IllegalStateException("manifestation_externalized_foreach_frame_accumulator_load_failed")

            val finalStack = baseStack.toMutableList()
            finalStack.add(ListIota(SpellList.LList(accumulator)))

            store.deleteLargeList(inputListId)
            store.deleteLargeList(accumulatorListId)

            if (context.debugTelemetry) {
                Manifestation.LOGGER.info(
                    "Manifestation externalized foreach complete: splinter={}, total={}, resultSize={}, stack={}",
                    sourceSplinterId,
                    totalCount,
                    accumulator.size,
                    finalStack.size
                )
            }

            CastResult(
                ListIota(SpellList.LList(bodyCode)),
                continuation,
                harness.image.withResetEscape().copy(stack = finalStack),
                emptyList(),
                ResolvedPatternType.EVALUATED,
                HexEvalSounds.THOTH
            )
        }
    }

    override fun breakDownwards(stack: List<Iota>): Pair<Boolean, List<Iota>> {
        val context = ManifestationSplinterFrameContext.requireCurrent()
        if (context.splinterId != sourceSplinterId || context.ownerId != owner) {
            throw IllegalStateException("manifestation_externalized_foreach_frame_context_mismatch")
        }

        val level = context.level
        val store = context.state
        val baseStack = when (val tags = baseStackTags) {
            null -> {
                if (context.debugTelemetry) {
                    Manifestation.LOGGER.warn(
                        "Manifestation externalized foreach break without base stack: splinter={}, cursor={}, total={}",
                        sourceSplinterId,
                        cursor,
                        totalCount
                    )
                }
                emptyList()
            }

            else -> deserializeTagListToIotasOrNull(tags, level, context.ownerPlayer)
                ?: throw IllegalStateException("manifestation_externalized_foreach_frame_break_base_stack_decode_failed")
        }

        val accumulatorEntry = store.getLargeListEntry(accumulatorListId)
            ?: throw IllegalStateException("manifestation_externalized_foreach_frame_break_missing_accumulator_list")
        val accumulator = readLargeListRangeAsIotasOrNull(
            store = store,
            listId = accumulatorListId,
            start = 0,
            count = accumulatorEntry.totalCount,
            level = level,
            owner = context.ownerPlayer,
            accessGameTime = level.gameTime
        ) ?: throw IllegalStateException("manifestation_externalized_foreach_frame_break_accumulator_load_failed")

        val out = baseStack.toMutableList()
        out.add(ListIota(SpellList.LList(accumulator + stack)))

        store.deleteLargeList(inputListId)
        store.deleteLargeList(accumulatorListId)

        if (context.debugTelemetry) {
            Manifestation.LOGGER.warn(
                "Manifestation externalized foreach break: splinter={}, cursor={}, total={}, stack={}, baseStack={}, result={}, outStack={}",
                sourceSplinterId,
                cursor,
                totalCount,
                stack.size,
                baseStack.size,
                accumulator.size,
                out.size
            )
        }

        return true to out
    }

    override fun serializeToNBT(): CompoundTag {
        return CompoundTag().apply {
            putUUID(OWNER_KEY, owner)
            putUUID(SOURCE_SPLINTER_ID_KEY, sourceSplinterId)
            putUUID(INPUT_LIST_ID_KEY, inputListId)
            putInt(CURSOR_KEY, cursor)
            putInt(TOTAL_COUNT_KEY, totalCount)
            put(BODY_CODE_KEY, copyToListTag(bodyCodeTags))
            baseStackTags?.let { put(BASE_STACK_KEY, copyToListTag(it)) }
            putUUID(ACCUMULATOR_LIST_ID_KEY, accumulatorListId)
        }
    }

    override fun size(): Int = 1

    override val type: ContinuationFrame.Type<*>
        get() = TYPE

    companion object {
        const val TYPE_ID: String = "manifestation:externalized_foreach"

        const val OWNER_KEY = "owner"
        const val SOURCE_SPLINTER_ID_KEY = "source_splinter_id"
        const val INPUT_LIST_ID_KEY = "input_list_id"
        const val CURSOR_KEY = "cursor"
        const val TOTAL_COUNT_KEY = "total_count"
        const val BODY_CODE_KEY = "body_code"
        const val BASE_STACK_KEY = "base_stack"
        const val ACCUMULATOR_LIST_ID_KEY = "accumulator_list_id"

        val TYPE: ContinuationFrame.Type<ManifestationExternalizedForEachFrame> =
            object : ContinuationFrame.Type<ManifestationExternalizedForEachFrame> {
                override fun deserializeFromNBT(tag: CompoundTag, world: ServerLevel): ManifestationExternalizedForEachFrame {
                    return deserializeData(tag)
                }
            }

        internal fun deserializeData(tag: CompoundTag): ManifestationExternalizedForEachFrame {
            if (!tag.hasUUID(INPUT_LIST_ID_KEY)) {
                throw invalidFrame("missing_input_list_id", tag)
            }
            if (!tag.hasUUID(ACCUMULATOR_LIST_ID_KEY)) {
                throw invalidFrame("missing_accumulator_list_id", tag)
            }
            if (!tag.hasUUID(SOURCE_SPLINTER_ID_KEY)) {
                throw invalidFrame("missing_source_splinter_id", tag)
            }
            if (!tag.hasUUID(OWNER_KEY)) {
                throw invalidFrame("missing_owner", tag)
            }

            val cursor = requireInt(tag, CURSOR_KEY)
            val totalCount = requireInt(tag, TOTAL_COUNT_KEY)

            if (cursor < 0) {
                throw invalidFrame("invalid_cursor", tag)
            }
            if (totalCount < 0) {
                throw invalidFrame("invalid_total_count", tag)
            }
            if (cursor > totalCount) {
                throw invalidFrame("cursor_exceeds_total_count", tag)
            }
            val bodyCodeTags = requireCompoundTagList(tag, BODY_CODE_KEY)
            val baseStackTags = if (tag.contains(BASE_STACK_KEY, Tag.TAG_LIST.toInt())) {
                requireCompoundTagList(tag, BASE_STACK_KEY)
            } else {
                null
            }
            return ManifestationExternalizedForEachFrame(
                owner = tag.getUUID(OWNER_KEY),
                sourceSplinterId = tag.getUUID(SOURCE_SPLINTER_ID_KEY),
                inputListId = tag.getUUID(INPUT_LIST_ID_KEY),
                cursor = cursor,
                totalCount = totalCount,
                bodyCodeTags = bodyCodeTags,
                baseStackTags = baseStackTags,
                accumulatorListId = tag.getUUID(ACCUMULATOR_LIST_ID_KEY)
            )
        }

        private fun requireInt(tag: CompoundTag, key: String): Int {
            if (!tag.contains(key, Tag.TAG_INT.toInt())) {
                throw invalidFrame("missing_$key", tag)
            }
            return tag.getInt(key)
        }

        private fun requireCompoundTagList(tag: CompoundTag, key: String): MutableList<CompoundTag> {
            if (!tag.contains(key, Tag.TAG_LIST.toInt())) {
                throw invalidFrame("missing_$key", tag)
            }
            val list = tag.getList(key, Tag.TAG_COMPOUND.toInt())
            val out = mutableListOf<CompoundTag>()
            for (idx in 0 until list.size) {
                val entry = list[idx]
                if (entry !is CompoundTag) {
                    throw invalidFrame("invalid_${key}_entry_type", tag)
                }
                out.add(entry.copy())
            }
            return out
        }

        private fun copyToListTag(tags: List<CompoundTag>): ListTag {
            val out = ListTag()
            for (tag in tags) {
                out.add(tag.copy())
            }
            return out
        }

        private fun copyCompoundTagListOrNull(tags: List<CompoundTag>?): MutableList<CompoundTag>? {
            if (tags == null) {
                return null
            }
            val out = mutableListOf<CompoundTag>()
            for (tag in tags) {
                out.add(tag.copy())
            }
            return out
        }

        private fun invalidFrame(reason: String, tag: CompoundTag): IllegalArgumentException {
            Manifestation.LOGGER.error(
                "Manifestation externalized foreach frame decode failed: reason={}, keys={}",
                reason,
                tag.allKeys
            )
            return IllegalArgumentException(reason)
        }

        private fun deserializeTagListToIotasOrNull(
            tags: List<CompoundTag>,
            level: ServerLevel,
            owner: net.minecraft.server.level.ServerPlayer
        ): MutableList<Iota>? {
            val out = mutableListOf<Iota>()
            for (tag in tags) {
                val iota = try {
                    IotaType.deserialize(tag.copy(), level)
                } catch (_: Throwable) {
                    return null
                } ?: return null
                try {
                    SplinterRuntime.throwIfOtherTrueNameEmbedded(iota, owner)
                } catch (_: Throwable) {
                    return null
                }
                out.add(iota)
            }
            return out
        }

        private fun serializeIotasOrNull(
            iotas: Iterable<Iota>,
            owner: net.minecraft.server.level.ServerPlayer
        ): MutableList<CompoundTag>? {
            val out = mutableListOf<CompoundTag>()
            for (iota in iotas) {
                try {
                    SplinterRuntime.throwIfOtherTrueNameEmbedded(iota, owner)
                } catch (_: Throwable) {
                    return null
                }
                val serialized = try {
                    IotaType.serialize(iota)
                } catch (_: Throwable) {
                    return null
                }
                if (serialized !is CompoundTag) {
                    return null
                }
                out.add(serialized.copy())
            }
            return out
        }

        private fun readLargeListItemAsIotaOrNull(
            store: SplinterStateStore,
            listId: UUID,
            index: Int,
            level: ServerLevel,
            owner: net.minecraft.server.level.ServerPlayer,
            accessGameTime: Long
        ): Iota? {
            if (index < 0) {
                return null
            }
            val tag = store.getLargeListItemTag(listId, index, accessGameTime) ?: return null
            val iota = try {
                IotaType.deserialize(tag.copy(), level)
            } catch (_: Throwable) {
                return null
            } ?: return null
            try {
                SplinterRuntime.throwIfOtherTrueNameEmbedded(iota, owner)
            } catch (_: Throwable) {
                return null
            }
            return iota
        }

        private fun readLargeListRangeAsIotasOrNull(
            store: SplinterStateStore,
            listId: UUID,
            start: Int,
            count: Int,
            level: ServerLevel,
            owner: net.minecraft.server.level.ServerPlayer,
            accessGameTime: Long
        ): MutableList<Iota>? {
            if (count < 0 || start < 0) {
                return null
            }
            val out = mutableListOf<Iota>()
            for (index in start until (start + count)) {
                val tag = store.getLargeListItemTag(listId, index, accessGameTime) ?: return null
                val iota = try {
                    IotaType.deserialize(tag.copy(), level)
                } catch (_: Throwable) {
                    return null
                } ?: return null
                try {
                    SplinterRuntime.throwIfOtherTrueNameEmbedded(iota, owner)
                } catch (_: Throwable) {
                    return null
                }
                out.add(iota)
            }
            return out
        }

    }
}
