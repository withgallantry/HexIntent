package com.bluup.manifestation.server

enum class SplinterPerformancePreset {
    SAFE,
    BALANCED,
    FAST,
    BLAST,
    CUSTOM;

    companion object {
        fun parseOrNull(raw: String?): SplinterPerformancePreset? {
            if (raw.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

data class SplinterResolvedTuning(
    val globalBudgetMicrosPerTick: Long,
    val opsPerSlice: Int,
    val sliceBudgetMicros: Long,
    val maxSlicesPerTick: Int,
    val maxRecordScansPerTick: Int,
    val emergencySliceMillis: Long,
    val maxTotalWorkUnits: Long,
    val largeListChunkSize: Int,
    val largeForeachChunkExecutionSize: Int,
    val safeInlineForeachRemainingCap: Int,
    val debugSliceTelemetry: Boolean
)

data class SplinterAdvancedConfig(
    val enabled: Boolean = false,
    val globalBudgetMicrosPerTick: Long = 45_000L,
    val opsPerSlice: Int = 25_000,
    val sliceBudgetMicros: Long = 45_000L,
    val maxSlicesPerTick: Int = 4,
    val maxRecordScansPerTick: Int = 512,
    val emergencySliceMillis: Long = 500L,
    val maxTotalWorkUnits: Long = 5_000_000L,
    val largeListChunkSize: Int = 4096,
    val largeForeachChunkExecutionSize: Int = 512,
    val safeInlineForeachRemainingCap: Int = 4096,
    val debugSliceTelemetry: Boolean = false
)

object SplinterPerformanceTuning {
    
    // This seems best for servers. Defaulted to this.
    private val SAFE = SplinterResolvedTuning(
        globalBudgetMicrosPerTick = 20_000L,
        opsPerSlice = 5_000,
        sliceBudgetMicros = 20_000L,
        maxSlicesPerTick = 2,
        maxRecordScansPerTick = 128,
        emergencySliceMillis = 250L,
        maxTotalWorkUnits = 1_000_000L,
        largeListChunkSize = 1024,
        largeForeachChunkExecutionSize = 128,
        safeInlineForeachRemainingCap = 1024,
        debugSliceTelemetry = false
    )

    private val BALANCED = SplinterResolvedTuning(
        globalBudgetMicrosPerTick = 45_000L,
        opsPerSlice = 25_000,
        sliceBudgetMicros = 45_000L,
        maxSlicesPerTick = 4,
        maxRecordScansPerTick = 512,
        emergencySliceMillis = 500L,
        maxTotalWorkUnits = 5_000_000L,
        largeListChunkSize = 4096,
        largeForeachChunkExecutionSize = 512,
        safeInlineForeachRemainingCap = 4096,
        debugSliceTelemetry = false
    )

    private val FAST = SplinterResolvedTuning(
        globalBudgetMicrosPerTick = 100_000L,
        opsPerSlice = 250_000,
        sliceBudgetMicros = 100_000L,
        maxSlicesPerTick = 8,
        maxRecordScansPerTick = 1_024,
        emergencySliceMillis = 2_000L,
        maxTotalWorkUnits = 100_000_000L,
        largeListChunkSize = 4096,
        largeForeachChunkExecutionSize = 2_048,
        safeInlineForeachRemainingCap = 4096,
        debugSliceTelemetry = false
    )

    // Blast has SOME limits but is effectively unthrottled.
    private val BLAST = SplinterResolvedTuning(
        globalBudgetMicrosPerTick = 10_000_000L,
        opsPerSlice = 50_000_000,
        sliceBudgetMicros = 10_000_000L,
        maxSlicesPerTick = 1,
        maxRecordScansPerTick = 512,
        emergencySliceMillis = 60_000L,
        maxTotalWorkUnits = Long.MAX_VALUE,
        largeListChunkSize = 4096,
        largeForeachChunkExecutionSize = 4096,
        safeInlineForeachRemainingCap = 1_000_000,
        debugSliceTelemetry = false
    )

    private val LEGACY_ADVANCED_TOP_LEVEL_KEYS = setOf(
        "splinterGlobalBudgetMicrosPerTick",
        "splinterOpsPerSlice",
        "splinterSliceBudgetMicros",
        "splinterMaxSlicesPerTick",
        "splinterMaxRecordScansPerTick",
        "splinterEmergencySliceMillis",
        "splinterMaxTotalWorkUnits",
        "splinterMaxTotalOps",
        "splinterLargeListChunkSize",
        "splinterLargeForeachChunkExecutionSize",
        "splinterSafeInlineForeachRemainingCap",
        "splinterDebugSliceTelemetry",
        "splinterExternalizedForEachFrameInnerStepsPerEvaluate"
    )

    fun detectLegacyAdvancedTopLevelKeys(presentKeys: Set<String>): Boolean {
        return presentKeys.any { it in LEGACY_ADVANCED_TOP_LEVEL_KEYS }
    }

    fun resolvePreset(
        presetRaw: String?,
        presetProvided: Boolean,
        hasLegacyTopLevelAdvancedKeys: Boolean,
        defaultPreset: SplinterPerformancePreset = SplinterPerformancePreset.SAFE
    ): SplinterPerformancePreset {
        val parsed = SplinterPerformancePreset.parseOrNull(presetRaw)
        if (!presetProvided && hasLegacyTopLevelAdvancedKeys) {
            return SplinterPerformancePreset.CUSTOM
        }
        return parsed ?: defaultPreset
    }

    fun resolveTuning(
        preset: SplinterPerformancePreset,
        advanced: SplinterAdvancedConfig
    ): SplinterResolvedTuning {
        val base = when (preset) {
            SplinterPerformancePreset.SAFE -> SAFE
            SplinterPerformancePreset.BALANCED -> BALANCED
            SplinterPerformancePreset.FAST -> FAST
            SplinterPerformancePreset.BLAST -> BLAST
            SplinterPerformancePreset.CUSTOM -> advanced.toResolved()
        }
        return clampMinimums(base)
    }

    fun balancedAdvancedDefaults(enabled: Boolean = false): SplinterAdvancedConfig {
        return SplinterAdvancedConfig(enabled = enabled)
    }

    fun safeAdvancedDefaults(enabled: Boolean = false): SplinterAdvancedConfig {
        return SplinterAdvancedConfig(enabled = enabled)
    }

    fun fromLegacyTopLevel(
        enabled: Boolean,
        globalBudgetMicrosPerTick: Long,
        opsPerSlice: Int,
        sliceBudgetMicros: Long,
        maxSlicesPerTick: Int,
        maxRecordScansPerTick: Int,
        emergencySliceMillis: Long,
        maxTotalWorkUnits: Long,
        largeListChunkSize: Int,
        largeForeachChunkExecutionSize: Int,
        safeInlineForeachRemainingCap: Int,
        debugSliceTelemetry: Boolean
    ): SplinterAdvancedConfig {
        return SplinterAdvancedConfig(
            enabled = enabled,
            globalBudgetMicrosPerTick = globalBudgetMicrosPerTick,
            opsPerSlice = opsPerSlice,
            sliceBudgetMicros = sliceBudgetMicros,
            maxSlicesPerTick = maxSlicesPerTick,
            maxRecordScansPerTick = maxRecordScansPerTick,
            emergencySliceMillis = emergencySliceMillis,
            maxTotalWorkUnits = maxTotalWorkUnits,
            largeListChunkSize = largeListChunkSize,
            largeForeachChunkExecutionSize = largeForeachChunkExecutionSize,
            safeInlineForeachRemainingCap = safeInlineForeachRemainingCap,
            debugSliceTelemetry = debugSliceTelemetry
        )
    }

    private fun SplinterAdvancedConfig.toResolved(): SplinterResolvedTuning {
        return SplinterResolvedTuning(
            globalBudgetMicrosPerTick = globalBudgetMicrosPerTick,
            opsPerSlice = opsPerSlice,
            sliceBudgetMicros = sliceBudgetMicros,
            maxSlicesPerTick = maxSlicesPerTick,
            maxRecordScansPerTick = maxRecordScansPerTick,
            emergencySliceMillis = emergencySliceMillis,
            maxTotalWorkUnits = maxTotalWorkUnits,
            largeListChunkSize = largeListChunkSize,
            largeForeachChunkExecutionSize = largeForeachChunkExecutionSize,
            safeInlineForeachRemainingCap = safeInlineForeachRemainingCap,
            debugSliceTelemetry = debugSliceTelemetry
        )
    }

    private fun clampMinimums(tuning: SplinterResolvedTuning): SplinterResolvedTuning {
        return tuning.copy(
            globalBudgetMicrosPerTick = tuning.globalBudgetMicrosPerTick.coerceAtLeast(1L),
            opsPerSlice = tuning.opsPerSlice.coerceAtLeast(1),
            sliceBudgetMicros = tuning.sliceBudgetMicros.coerceAtLeast(1L),
            maxSlicesPerTick = tuning.maxSlicesPerTick.coerceAtLeast(1),
            maxRecordScansPerTick = tuning.maxRecordScansPerTick.coerceAtLeast(1),
            emergencySliceMillis = tuning.emergencySliceMillis.coerceAtLeast(1L),
            maxTotalWorkUnits = tuning.maxTotalWorkUnits.coerceAtLeast(1L),
            largeListChunkSize = tuning.largeListChunkSize.coerceAtLeast(1),
            largeForeachChunkExecutionSize = tuning.largeForeachChunkExecutionSize.coerceAtLeast(1),
            safeInlineForeachRemainingCap = tuning.safeInlineForeachRemainingCap.coerceAtLeast(1)
        )
    }
}
