package com.bluup.manifestation.server.splinter

import com.bluup.manifestation.server.ManifestationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestationConfigPresetSurfaceTest {
    @Test
    fun configExposesPresetGetter() {
        val hasPresetGetter = ManifestationConfig::class.java.methods.any {
            it.name == "splinterPerformancePreset" && it.parameterCount == 0
        }
        assertTrue(hasPresetGetter)
    }

    @Test
    fun configExposesResolvedTuningLoggerHook() {
        val hasLogHook = ManifestationConfig::class.java.methods.any {
            it.name == "maybeLogResolvedSplinterTuningOnce" && it.parameterCount == 0
        }
        assertTrue(hasLogHook)
    }

    @Test
    fun writableConfigDoesNotExposeLowLevelTopLevelSplinterKnobs() {
        val writable = ManifestationConfig::class.java.declaredClasses.firstOrNull {
            it.simpleName == "WritableConfig"
        }
        assertTrue(writable != null)

        val fieldNames = writable.declaredFields.map { it.name }.toSet()
        assertTrue("splinterPerformancePreset" in fieldNames)
        assertTrue("splinterAdvanced" in fieldNames)
        assertFalse("splinterSafeInlineForeachRemainingCap" in fieldNames)
        assertFalse("splinterLargeForeachChunkExecutionSize" in fieldNames)
        assertFalse("splinterGlobalBudgetMicrosPerTick" in fieldNames)

        val writableAdvanced = ManifestationConfig::class.java.declaredClasses.firstOrNull {
            it.simpleName == "WritableSplinterAdvancedConfig"
        }
        assertTrue(writableAdvanced != null)
        val writableAdvancedFieldNames = writableAdvanced.declaredFields.map { it.name }.toSet()
        assertFalse("externalizedForEachInnerStepsPerEvaluate" in writableAdvancedFieldNames)
    }
}
