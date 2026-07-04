package com.bluup.manifestation.server.splinter

import com.bluup.manifestation.server.KotlinNbtCompat
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import java.util.UUID

class SplinterStateStore : SavedData() {
    data class SplinterLargeListEntry(
        val listId: UUID,
        val owner: UUID,
        val sourceSplinterId: UUID,
        var totalCount: Int,
        val chunkSize: Int,
        val createdGameTime: Long,
        var lastAccessGameTime: Long,
        val chunks: MutableList<ListTag>
    )

    data class SplinterRecord(
        val id: UUID,
        val owner: UUID,
        var dimensionId: String,
        var position: Vec3,
        var anchorPosition: Vec3?,
        var circleImpetusPos: Vec3?,
        var castAtGameTime: Long,
        var castingHand: InteractionHand,
        val payloadTags: MutableList<CompoundTag>,
        var ravenmindTag: CompoundTag?,
        var started: Boolean = false,
        var imageTag: CompoundTag? = null,
        val continuationTags: MutableList<CompoundTag> = mutableListOf(),
        var createdGameTime: Long = castAtGameTime,
        var lastRunGameTime: Long = castAtGameTime,
        var lastProgressGameTime: Long = castAtGameTime,
        var totalHexOpsRun: Long = 0L,
        var totalFrameStepsRun: Long = 0L,
        var overBudgetCount: Int = 0,
        var ambitRadius: Double = SplinterCastEnv.SPLINTER_AMBIT_RADIUS,
        var allowRenew: Boolean = true,
        var lastObservedStackSize: Int? = null,
        var lastObservedContinuationSize: Int? = null,
        var pendingRenewPosition: Vec3? = null,
        var pendingRenewDelayTicks: Long? = null,
        var pendingRenewRequestedGameTime: Long? = null
    )

    private val splintersByOwner: MutableMap<UUID, MutableMap<UUID, SplinterRecord>> = mutableMapOf()
    private val largeListsById: MutableMap<UUID, SplinterLargeListEntry> = mutableMapOf()

    fun allByOwner(owner: UUID): List<SplinterRecord> = splintersByOwner[owner]?.values?.toList() ?: listOf()

    fun allOwners(): Set<UUID> = splintersByOwner.keys

    fun count(owner: UUID): Int = splintersByOwner[owner]?.size ?: 0

    fun get(owner: UUID, id: UUID): SplinterRecord? = splintersByOwner[owner]?.get(id)

    fun put(record: SplinterRecord) {
        val map = splintersByOwner.getOrPut(record.owner) { mutableMapOf() }
        map[record.id] = record
        setDirty()
    }

    fun putLargeList(
        owner: UUID,
        sourceSplinterId: UUID,
        itemTags: List<CompoundTag>,
        createdGameTime: Long,
        chunkSize: Int
    ): UUID {
        val listId = UUID.randomUUID()
        val safeChunkSize = chunkSize.coerceAtLeast(1)
        val chunks = mutableListOf<ListTag>()
        var index = 0
        while (index < itemTags.size) {
            val chunk = ListTag()
            val endExclusive = minOf(index + safeChunkSize, itemTags.size)
            for (itemIndex in index until endExclusive) {
                chunk.add(itemTags[itemIndex].copy())
            }
            chunks.add(chunk)
            index = endExclusive
        }

        largeListsById[listId] = SplinterLargeListEntry(
            listId = listId,
            owner = owner,
            sourceSplinterId = sourceSplinterId,
            totalCount = itemTags.size,
            chunkSize = safeChunkSize,
            createdGameTime = createdGameTime,
            lastAccessGameTime = createdGameTime,
            chunks = chunks
        )
        setDirty()
        return listId
    }

    fun getLargeListEntry(listId: UUID): SplinterLargeListEntry? = largeListsById[listId]

    fun countLargeListsForOwner(owner: UUID): Int = largeListsById.values.count { it.owner == owner }

    fun countLargeListsForSplinter(sourceSplinterId: UUID): Int =
        largeListsById.values.count { it.sourceSplinterId == sourceSplinterId }

    fun totalLargeListItemsForOwner(owner: UUID): Int = largeListsById.values
        .filter { it.owner == owner }
        .sumOf { it.totalCount }

    fun totalLargeListItemsForSplinter(sourceSplinterId: UUID): Int = largeListsById.values
        .filter { it.sourceSplinterId == sourceSplinterId }
        .sumOf { it.totalCount }

    fun getLargeListItemTag(listId: UUID, index: Int, accessGameTime: Long): CompoundTag? {
        val entry = largeListsById[listId] ?: return null
        if (index < 0 || index >= entry.totalCount) {
            return null
        }

        val chunkIndex = index / entry.chunkSize
        val inChunkIndex = index % entry.chunkSize
        val chunk = entry.chunks.getOrNull(chunkIndex) ?: return null
        if (inChunkIndex >= chunk.size) {
            return null
        }

        entry.lastAccessGameTime = accessGameTime
        val tag = chunk.getCompound(inChunkIndex).copy()
        return tag
    }

    fun getChunk(listId: UUID, chunkIndex: Int, accessGameTime: Long): List<CompoundTag> {
        val entry = largeListsById[listId] ?: return listOf()
        val chunk = entry.chunks.getOrNull(chunkIndex) ?: return listOf()
        entry.lastAccessGameTime = accessGameTime
        val out = mutableListOf<CompoundTag>()
        for (index in 0 until chunk.size) {
            out.add(chunk.getCompound(index).copy())
        }
        return out
    }

    fun touchLargeListAccess(listId: UUID, accessGameTime: Long, persist: Boolean = false): Boolean {
        val entry = largeListsById[listId] ?: return false
        if (accessGameTime <= entry.lastAccessGameTime) {
            return false
        }
        entry.lastAccessGameTime = accessGameTime
        if (persist) {
            setDirty()
        }
        return true
    }

    fun appendLargeListItems(listId: UUID, itemTags: List<CompoundTag>, accessGameTime: Long): Boolean {
        val entry = largeListsById[listId] ?: return false
        if (itemTags.isEmpty()) {
            entry.lastAccessGameTime = accessGameTime
            return true
        }

        for (itemTag in itemTags) {
            val targetChunk = if (entry.chunks.isEmpty() || entry.chunks.last().size >= entry.chunkSize) {
                ListTag().also(entry.chunks::add)
            } else {
                entry.chunks.last()
            }
            targetChunk.add(itemTag.copy())
            entry.totalCount += 1
        }
        entry.lastAccessGameTime = accessGameTime
        setDirty()
        return true
    }

    fun deleteLargeList(listId: UUID): Boolean {
        val removed = largeListsById.remove(listId)
        if (removed != null) {
            setDirty()
            return true
        }
        return false
    }

    fun deleteLargeListsForSplinter(sourceSplinterId: UUID): Int {
        val ids = largeListsById.values
            .filter { it.sourceSplinterId == sourceSplinterId }
            .map { it.listId }
        for (id in ids) {
            largeListsById.remove(id)
        }
        if (ids.isNotEmpty()) {
            setDirty()
        }
        return ids.size
    }

    fun cleanupOrphanedLargeLists(activeSplinterIds: Set<UUID>, currentGameTime: Long, staleAfterTicks: Long): Int {
        val staleCutoff = currentGameTime - staleAfterTicks.coerceAtLeast(0L)
        val activeReferencedListIds = mutableSetOf<UUID>()
        for (records in splintersByOwner.values) {
            for (record in records.values) {
                activeReferencedListIds.addAll(
                    SplinterLargeForEachRunner.collectReferencedLargeListIds(record.continuationTags)
                )
            }
        }

        val ids = largeListsById.values
            .filter { entry ->
                entry.sourceSplinterId !in activeSplinterIds ||
                    (entry.listId !in activeReferencedListIds && entry.lastAccessGameTime < staleCutoff)
            }
            .map { it.listId }

        for (id in ids) {
            largeListsById.remove(id)
        }
        if (ids.isNotEmpty()) {
            setDirty()
        }
        return ids.size
    }

    private fun putLoaded(record: SplinterRecord) {
        val map = splintersByOwner.getOrPut(record.owner) { mutableMapOf() }
        map[record.id] = record
    }

    fun remove(owner: UUID, id: UUID): SplinterRecord? {
        val map = splintersByOwner[owner] ?: return null
        val removed = map.remove(id)
        if (map.isEmpty()) {
            splintersByOwner.remove(owner)
        }
        if (removed != null) {
            setDirty()
        }
        return removed
    }

    fun removeOwner(owner: UUID): Boolean {
        val removed = splintersByOwner.remove(owner)
        if (removed != null) {
            setDirty()
            return true
        }
        return false
    }

    fun markDirty() {
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((_, records) in splintersByOwner) {
            for ((_, record) in records) {
                val out = CompoundTag()
                KotlinNbtCompat.putUUID(out, "id", record.id)
                KotlinNbtCompat.putUUID(out, "owner", record.owner)
                KotlinNbtCompat.putString(out, "dimension", record.dimensionId)
                KotlinNbtCompat.putDouble(out, "x", record.position.x)
                KotlinNbtCompat.putDouble(out, "y", record.position.y)
                KotlinNbtCompat.putDouble(out, "z", record.position.z)
                if (record.anchorPosition != null) {
                    KotlinNbtCompat.putDouble(out, "anchor_x", record.anchorPosition!!.x)
                    KotlinNbtCompat.putDouble(out, "anchor_y", record.anchorPosition!!.y)
                    KotlinNbtCompat.putDouble(out, "anchor_z", record.anchorPosition!!.z)
                }
                if (record.circleImpetusPos != null) {
                    KotlinNbtCompat.putDouble(out, "circle_impetus_x", record.circleImpetusPos!!.x)
                    KotlinNbtCompat.putDouble(out, "circle_impetus_y", record.circleImpetusPos!!.y)
                    KotlinNbtCompat.putDouble(out, "circle_impetus_z", record.circleImpetusPos!!.z)
                }
                KotlinNbtCompat.putLong(out, "cast_at", record.castAtGameTime)
                KotlinNbtCompat.putString(out, "hand", record.castingHand.name)

                val payload = ListTag()
                for (iotaTag in record.payloadTags) {
                    payload.add(iotaTag.copy())
                }
                KotlinNbtCompat.put(out, "payload", payload)
                if (record.ravenmindTag != null) {
                    KotlinNbtCompat.put(out, "ravenmind", record.ravenmindTag!!.copy())
                }

                KotlinNbtCompat.putBoolean(out, "started", record.started)
                if (record.imageTag != null) {
                    KotlinNbtCompat.put(out, "image", record.imageTag!!.copy())
                }

                val continuation = ListTag()
                for (frameTag in record.continuationTags) {
                    continuation.add(frameTag.copy())
                }
                KotlinNbtCompat.put(out, "continuation", continuation)

                KotlinNbtCompat.putLong(out, "created_at", record.createdGameTime)
                KotlinNbtCompat.putLong(out, "last_run", record.lastRunGameTime)
                KotlinNbtCompat.putLong(out, "last_progress", record.lastProgressGameTime)
                KotlinNbtCompat.putLong(out, "total_hex_ops", record.totalHexOpsRun)
                KotlinNbtCompat.putLong(out, "total_frame_steps", record.totalFrameStepsRun)
                KotlinNbtCompat.putInt(out, "over_budget_count", record.overBudgetCount)
                KotlinNbtCompat.putDouble(out, "ambit_radius", record.ambitRadius)
                KotlinNbtCompat.putBoolean(out, "allow_renew", record.allowRenew)
                if (record.lastObservedStackSize != null) {
                    KotlinNbtCompat.putInt(out, "last_observed_stack_size", record.lastObservedStackSize!!)
                }
                if (record.lastObservedContinuationSize != null) {
                    KotlinNbtCompat.putInt(out, "last_observed_continuation_size", record.lastObservedContinuationSize!!)
                }
                if (record.pendingRenewPosition != null) {
                    KotlinNbtCompat.putDouble(out, "pending_renew_x", record.pendingRenewPosition!!.x)
                    KotlinNbtCompat.putDouble(out, "pending_renew_y", record.pendingRenewPosition!!.y)
                    KotlinNbtCompat.putDouble(out, "pending_renew_z", record.pendingRenewPosition!!.z)
                }
                if (record.pendingRenewDelayTicks != null) {
                    KotlinNbtCompat.putLong(out, "pending_renew_delay", record.pendingRenewDelayTicks!!)
                }
                if (record.pendingRenewRequestedGameTime != null) {
                    KotlinNbtCompat.putLong(out, "pending_renew_requested_at", record.pendingRenewRequestedGameTime!!)
                }
                list.add(out)
            }
        }
        KotlinNbtCompat.put(tag, "splinters", list)

        val largeLists = ListTag()
        for (entry in largeListsById.values) {
            val out = CompoundTag()
            KotlinNbtCompat.putUUID(out, "list_id", entry.listId)
            KotlinNbtCompat.putUUID(out, "owner", entry.owner)
            KotlinNbtCompat.putUUID(out, "source_splinter_id", entry.sourceSplinterId)
            KotlinNbtCompat.putInt(out, "total_count", entry.totalCount)
            KotlinNbtCompat.putInt(out, "chunk_size", entry.chunkSize)
            KotlinNbtCompat.putLong(out, "created_at", entry.createdGameTime)
            KotlinNbtCompat.putLong(out, "last_access", entry.lastAccessGameTime)

            val chunkList = ListTag()
            for (chunk in entry.chunks) {
                val chunkTag = CompoundTag()
                KotlinNbtCompat.put(chunkTag, "items", copyListTag(chunk))
                chunkList.add(chunkTag)
            }
            KotlinNbtCompat.put(out, "chunks", chunkList)
            largeLists.add(out)
        }
        KotlinNbtCompat.put(tag, "large_lists", largeLists)
        return tag
    }

    companion object {
        private const val DATA_NAME = "manifestation_splinters"

        fun get(server: MinecraftServer): SplinterStateStore {
            val storage = server.overworld().dataStorage
            return storage.computeIfAbsent(::load, ::SplinterStateStore, DATA_NAME)
        }

        internal fun loadFromTag(tag: CompoundTag): SplinterStateStore {
            val out = SplinterStateStore()
            val list = KotlinNbtCompat.getList(tag, "splinters", Tag.TAG_COMPOUND.toInt())
            for (entry in list) {
                val t = entry as? CompoundTag ?: continue
                if (!KotlinNbtCompat.hasUUID(t, "id") || !KotlinNbtCompat.hasUUID(t, "owner")) {
                    continue
                }

                val id = KotlinNbtCompat.getUUID(t, "id")
                val owner = KotlinNbtCompat.getUUID(t, "owner")
                val dimension = KotlinNbtCompat.getString(t, "dimension")
                val pos = Vec3(KotlinNbtCompat.getDouble(t, "x"), KotlinNbtCompat.getDouble(t, "y"), KotlinNbtCompat.getDouble(t, "z"))
                val anchorPos = if (KotlinNbtCompat.contains(t, "anchor_x", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "anchor_y", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "anchor_z", Tag.TAG_DOUBLE.toInt())
                ) {
                    Vec3(KotlinNbtCompat.getDouble(t, "anchor_x"), KotlinNbtCompat.getDouble(t, "anchor_y"), KotlinNbtCompat.getDouble(t, "anchor_z"))
                } else {
                    null
                }
                val castAt = KotlinNbtCompat.getLong(t, "cast_at")
                val handName = KotlinNbtCompat.getString(t, "hand")
                val hand = runCatching { InteractionHand.valueOf(handName) }.getOrElse { InteractionHand.MAIN_HAND }
                val circleImpetusPos = if (KotlinNbtCompat.contains(t, "circle_impetus_x", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "circle_impetus_y", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "circle_impetus_z", Tag.TAG_DOUBLE.toInt())
                ) {
                    Vec3(KotlinNbtCompat.getDouble(t, "circle_impetus_x"), KotlinNbtCompat.getDouble(t, "circle_impetus_y"), KotlinNbtCompat.getDouble(t, "circle_impetus_z"))
                } else {
                    null
                }

                val payload = mutableListOf<CompoundTag>()
                if (KotlinNbtCompat.contains(t, "payload", Tag.TAG_LIST.toInt())) {
                    val payloadList = KotlinNbtCompat.getList(t, "payload", Tag.TAG_COMPOUND.toInt())
                    for (i in 0 until payloadList.size) {
                        payload.add(payloadList.getCompound(i).copy())
                    }
                }
                val ravenmind = if (KotlinNbtCompat.contains(t, "ravenmind", Tag.TAG_COMPOUND.toInt())) {
                    KotlinNbtCompat.getCompound(t, "ravenmind").copy()
                } else {
                    null
                }

                val imageTag = if (KotlinNbtCompat.contains(t, "image", Tag.TAG_COMPOUND.toInt())) {
                    KotlinNbtCompat.getCompound(t, "image").copy()
                } else {
                    null
                }

                val continuation = mutableListOf<CompoundTag>()
                if (KotlinNbtCompat.contains(t, "continuation", Tag.TAG_LIST.toInt())) {
                    val continuationList = KotlinNbtCompat.getList(t, "continuation", Tag.TAG_COMPOUND.toInt())
                    for (i in 0 until continuationList.size) {
                        continuation.add(continuationList.getCompound(i).copy())
                    }
                }

                val inferredStarted = imageTag != null || continuation.isNotEmpty()
                val started = if (KotlinNbtCompat.contains(t, "started", Tag.TAG_BYTE.toInt())) {
                    KotlinNbtCompat.getBoolean(t, "started")
                } else {
                    inferredStarted
                }

                val createdAt = if (KotlinNbtCompat.contains(t, "created_at", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "created_at")
                } else {
                    castAt
                }
                val lastRun = if (KotlinNbtCompat.contains(t, "last_run", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "last_run")
                } else {
                    castAt
                }
                val lastProgress = if (KotlinNbtCompat.contains(t, "last_progress", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "last_progress")
                } else {
                    castAt
                }
                val totalHexOps = if (KotlinNbtCompat.contains(t, "total_hex_ops", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "total_hex_ops")
                } else {
                    0L
                }
                val totalFrameSteps = if (KotlinNbtCompat.contains(t, "total_frame_steps", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "total_frame_steps")
                } else {
                    0L
                }
                val overBudgetCount = if (KotlinNbtCompat.contains(t, "over_budget_count", Tag.TAG_INT.toInt())) {
                    KotlinNbtCompat.getInt(t, "over_budget_count")
                } else {
                    0
                }
                val ambitRadius = if (KotlinNbtCompat.contains(t, "ambit_radius", Tag.TAG_DOUBLE.toInt())) {
                    KotlinNbtCompat.getDouble(t, "ambit_radius")
                } else {
                    SplinterCastEnv.SPLINTER_AMBIT_RADIUS
                }
                val allowRenew = if (KotlinNbtCompat.contains(t, "allow_renew", Tag.TAG_BYTE.toInt())) {
                    KotlinNbtCompat.getBoolean(t, "allow_renew")
                } else {
                    true
                }
                val lastObservedStackSize = if (KotlinNbtCompat.contains(t, "last_observed_stack_size", Tag.TAG_INT.toInt())) {
                    KotlinNbtCompat.getInt(t, "last_observed_stack_size")
                } else {
                    null
                }
                val lastObservedContinuationSize = if (KotlinNbtCompat.contains(t, "last_observed_continuation_size", Tag.TAG_INT.toInt())) {
                    KotlinNbtCompat.getInt(t, "last_observed_continuation_size")
                } else {
                    null
                }
                val pendingRenewPosition = if (KotlinNbtCompat.contains(t, "pending_renew_x", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "pending_renew_y", Tag.TAG_DOUBLE.toInt())
                    && KotlinNbtCompat.contains(t, "pending_renew_z", Tag.TAG_DOUBLE.toInt())
                ) {
                    Vec3(
                        KotlinNbtCompat.getDouble(t, "pending_renew_x"),
                        KotlinNbtCompat.getDouble(t, "pending_renew_y"),
                        KotlinNbtCompat.getDouble(t, "pending_renew_z")
                    )
                } else {
                    null
                }
                val pendingRenewDelayTicks = if (KotlinNbtCompat.contains(t, "pending_renew_delay", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "pending_renew_delay")
                } else {
                    null
                }
                val pendingRenewRequestedGameTime = if (KotlinNbtCompat.contains(t, "pending_renew_requested_at", Tag.TAG_LONG.toInt())) {
                    KotlinNbtCompat.getLong(t, "pending_renew_requested_at")
                } else {
                    null
                }

                val record = SplinterRecord(
                    id = id,
                    owner = owner,
                    dimensionId = dimension,
                    position = pos,
                    anchorPosition = anchorPos,
                    circleImpetusPos = circleImpetusPos,
                    castAtGameTime = castAt,
                    castingHand = hand,
                    payloadTags = payload,
                    ravenmindTag = ravenmind,
                    started = if (imageTag == null && continuation.isEmpty()) false else started,
                    imageTag = imageTag,
                    continuationTags = continuation,
                    createdGameTime = createdAt,
                    lastRunGameTime = lastRun,
                    lastProgressGameTime = lastProgress,
                    totalHexOpsRun = totalHexOps,
                    totalFrameStepsRun = totalFrameSteps,
                    overBudgetCount = overBudgetCount,
                    ambitRadius = ambitRadius,
                    allowRenew = allowRenew,
                    lastObservedStackSize = lastObservedStackSize,
                    lastObservedContinuationSize = lastObservedContinuationSize,
                    pendingRenewPosition = pendingRenewPosition,
                    pendingRenewDelayTicks = pendingRenewDelayTicks,
                    pendingRenewRequestedGameTime = pendingRenewRequestedGameTime
                )
                out.putLoaded(record)
            }

            if (KotlinNbtCompat.contains(tag, "large_lists", Tag.TAG_LIST.toInt())) {
                val largeListEntries = KotlinNbtCompat.getList(tag, "large_lists", Tag.TAG_COMPOUND.toInt())
                for (entry in largeListEntries) {
                    val t = entry as? CompoundTag ?: continue
                    if (!KotlinNbtCompat.hasUUID(t, "list_id") ||
                        !KotlinNbtCompat.hasUUID(t, "owner") ||
                        !KotlinNbtCompat.hasUUID(t, "source_splinter_id")
                    ) {
                        continue
                    }

                    val listId = KotlinNbtCompat.getUUID(t, "list_id")
                    val owner = KotlinNbtCompat.getUUID(t, "owner")
                    val sourceSplinterId = KotlinNbtCompat.getUUID(t, "source_splinter_id")
                    val totalCount = KotlinNbtCompat.getInt(t, "total_count")
                    val chunkSize = KotlinNbtCompat.getInt(t, "chunk_size").coerceAtLeast(1)
                    val createdAt = KotlinNbtCompat.getLong(t, "created_at")
                    val lastAccess = if (KotlinNbtCompat.contains(t, "last_access", Tag.TAG_LONG.toInt())) {
                        KotlinNbtCompat.getLong(t, "last_access")
                    } else {
                        createdAt
                    }
                    val chunks = mutableListOf<ListTag>()
                    if (KotlinNbtCompat.contains(t, "chunks", Tag.TAG_LIST.toInt())) {
                        val chunkEntries = KotlinNbtCompat.getList(t, "chunks", Tag.TAG_COMPOUND.toInt())
                        for (chunkEntry in chunkEntries) {
                            val chunkTag = chunkEntry as? CompoundTag ?: continue
                            val items = if (KotlinNbtCompat.contains(chunkTag, "items", Tag.TAG_LIST.toInt())) {
                                KotlinNbtCompat.getList(chunkTag, "items", Tag.TAG_COMPOUND.toInt())
                            } else {
                                ListTag()
                            }
                            chunks.add(copyListTag(items))
                        }
                    }

                    out.largeListsById[listId] = SplinterLargeListEntry(
                        listId = listId,
                        owner = owner,
                        sourceSplinterId = sourceSplinterId,
                        totalCount = totalCount,
                        chunkSize = chunkSize,
                        createdGameTime = createdAt,
                        lastAccessGameTime = lastAccess,
                        chunks = chunks
                    )
                }
            }
            return out
        }

        private fun load(tag: CompoundTag): SplinterStateStore = loadFromTag(tag)

        private fun compoundTagList(tags: List<CompoundTag>): ListTag {
            val out = ListTag()
            for (tag in tags) {
                out.add(tag.copy())
            }
            return out
        }

        private fun readCompoundTagList(tag: CompoundTag, key: String): MutableList<CompoundTag> {
            val out = mutableListOf<CompoundTag>()
            if (!KotlinNbtCompat.contains(tag, key, Tag.TAG_LIST.toInt())) {
                return out
            }

            val list = KotlinNbtCompat.getList(tag, key, Tag.TAG_COMPOUND.toInt())
            for (index in 0 until list.size) {
                out.add(list.getCompound(index).copy())
            }
            return out
        }

        private fun copyListTag(list: ListTag): ListTag {
            val out = ListTag()
            for (index in 0 until list.size) {
                out.add(list[index].copy())
            }
            return out
        }
    }
}
