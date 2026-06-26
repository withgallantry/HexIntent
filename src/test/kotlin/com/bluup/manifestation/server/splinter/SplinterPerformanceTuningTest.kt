package com.bluup.manifestation.server.splinter

import com.bluup.manifestation.server.SplinterAdvancedConfig
import com.bluup.manifestation.server.SplinterPerformancePreset
import com.bluup.manifestation.server.SplinterPerformanceTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplinterPerformanceTuningTest {
    @Test
    fun missingPresetDefaultsToSafe() {
        val preset = SplinterPerformanceTuning.resolvePreset(
            presetRaw = null,
            presetProvided = false,
            hasLegacyTopLevelAdvancedKeys = false
        )

        assertEquals(SplinterPerformancePreset.SAFE, preset)
    }

    @Test
    fun invalidPresetFallsBackToSafe() {
        val preset = SplinterPerformanceTuning.resolvePreset(
            presetRaw = "nonsense",
            presetProvided = true,
            hasLegacyTopLevelAdvancedKeys = false
        )

        assertEquals(SplinterPerformancePreset.SAFE, preset)
    }

    @Test
    fun safeBalancedFastBlastResolveExpectedCoreValues() {
        val advanced = SplinterAdvancedConfig()

        val safe = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.SAFE, advanced)
        assertEquals(20_000L, safe.globalBudgetMicrosPerTick)
        assertEquals(5_000, safe.opsPerSlice)
        assertEquals(2, safe.maxSlicesPerTick)
        assertEquals(128, safe.largeForeachChunkExecutionSize)

        val balanced = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.BALANCED, advanced)
        assertEquals(45_000L, balanced.globalBudgetMicrosPerTick)
        assertEquals(25_000, balanced.opsPerSlice)
        assertEquals(4, balanced.maxSlicesPerTick)
        assertEquals(512, balanced.largeForeachChunkExecutionSize)
        assertEquals(4_096, balanced.safeInlineForeachRemainingCap)

        val fast = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.FAST, advanced)
        assertEquals(100_000L, fast.globalBudgetMicrosPerTick)
        assertEquals(250_000, fast.opsPerSlice)
        assertEquals(8, fast.maxSlicesPerTick)
        assertEquals(2_048, fast.largeForeachChunkExecutionSize)

        val blast = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.BLAST, advanced)
        assertEquals(10_000_000L, blast.globalBudgetMicrosPerTick)
        assertEquals(50_000_000, blast.opsPerSlice)
        assertEquals(1, blast.maxSlicesPerTick)
        assertEquals(4_096, blast.largeForeachChunkExecutionSize)
        assertEquals(1_000_000, blast.safeInlineForeachRemainingCap)
    }

    @Test
    fun blastIsEffectivelyUnthrottledComparedToBalanced() {
        val advanced = SplinterAdvancedConfig()
        val balanced = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.BALANCED, advanced)
        val blast = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.BLAST, advanced)

        assertTrue(blast.globalBudgetMicrosPerTick > balanced.globalBudgetMicrosPerTick)
        assertTrue(blast.opsPerSlice > balanced.opsPerSlice)
        assertTrue(blast.safeInlineForeachRemainingCap > balanced.safeInlineForeachRemainingCap)
        assertTrue(blast.largeForeachChunkExecutionSize >= balanced.largeForeachChunkExecutionSize)
        assertTrue(blast.sliceBudgetMicros > balanced.sliceBudgetMicros)
    }

    @Test
    fun legacyTopLevelKeysWithoutPresetInferCustom() {
        val preset = SplinterPerformanceTuning.resolvePreset(
            presetRaw = null,
            presetProvided = false,
            hasLegacyTopLevelAdvancedKeys = true
        )

        assertEquals(SplinterPerformancePreset.CUSTOM, preset)
    }

    @Test
    fun explicitPresetWinsOverLegacyTopLevelKeys() {
        val preset = SplinterPerformanceTuning.resolvePreset(
            presetRaw = "blast",
            presetProvided = true,
            hasLegacyTopLevelAdvancedKeys = true
        )

        assertEquals(SplinterPerformancePreset.BLAST, preset)
    }

    @Test
    fun nonCustomPresetIgnoresAdvancedOverrides() {
        val advanced = SplinterAdvancedConfig(
            enabled = true,
            globalBudgetMicrosPerTick = 1,
            opsPerSlice = 1,
            sliceBudgetMicros = 1,
            maxSlicesPerTick = 1,
            maxRecordScansPerTick = 1,
            emergencySliceMillis = 1,
            maxTotalWorkUnits = 1,
            largeListChunkSize = 1,
            largeForeachChunkExecutionSize = 1,
            safeInlineForeachRemainingCap = 1,
            debugSliceTelemetry = true
        )

        val balanced = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.BALANCED, advanced)
        assertEquals(45_000L, balanced.globalBudgetMicrosPerTick)
        assertEquals(25_000, balanced.opsPerSlice)
        assertFalse(balanced.debugSliceTelemetry)
    }

    @Test
    fun customPresetUsesAdvancedOverrides() {
        val advanced = SplinterAdvancedConfig(
            enabled = true,
            globalBudgetMicrosPerTick = 222L,
            opsPerSlice = 333,
            sliceBudgetMicros = 444L,
            maxSlicesPerTick = 555,
            maxRecordScansPerTick = 666,
            emergencySliceMillis = 777L,
            maxTotalWorkUnits = 888L,
            largeListChunkSize = 999,
            largeForeachChunkExecutionSize = 111,
            safeInlineForeachRemainingCap = 222,
            debugSliceTelemetry = true
        )

        val custom = SplinterPerformanceTuning.resolveTuning(SplinterPerformancePreset.CUSTOM, advanced)

        assertEquals(222L, custom.globalBudgetMicrosPerTick)
        assertEquals(333, custom.opsPerSlice)
        assertEquals(444L, custom.sliceBudgetMicros)
        assertEquals(555, custom.maxSlicesPerTick)
        assertEquals(666, custom.maxRecordScansPerTick)
        assertEquals(777L, custom.emergencySliceMillis)
        assertEquals(888L, custom.maxTotalWorkUnits)
        assertEquals(999, custom.largeListChunkSize)
        assertEquals(111, custom.largeForeachChunkExecutionSize)
        assertEquals(222, custom.safeInlineForeachRemainingCap)
        assertTrue(custom.debugSliceTelemetry)
    }

    @Test
    fun legacyKeyDetectionFindsOldTopLevelFields() {
        val present = setOf("splinterGlobalBudgetMicrosPerTick", "menuOpenLoopWindowMs")
        assertTrue(SplinterPerformanceTuning.detectLegacyAdvancedTopLevelKeys(present))

        val absent = setOf("menuOpenLoopWindowMs", "splinterPerformancePreset")
        assertFalse(SplinterPerformanceTuning.detectLegacyAdvancedTopLevelKeys(absent))
    }
}
