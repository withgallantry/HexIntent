package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Minimal JSON config for server-side Manifestation behavior.
 */
object ManifestationConfig {
    private const val DEFAULT_MENU_LOOP_WINDOW_MS = 1400L
    private const val DEFAULT_MENU_LOOP_TRIGGER_COUNT = 3
    private const val DEFAULT_INTENT_RELAY_MAX_RANGE_BLOCKS = -1
    private const val DEFAULT_INTENT_RELAY_COOLDOWN_TICKS = 4
    private const val DEFAULT_INTENT_RELAY_STEP_TRIGGER_ENABLED = true
    private const val DEFAULT_PORTAL_LIVE_VIEW_ENABLED = true
    private const val DEFAULT_PORTAL_LIVE_VIEW_COLS = 48
    private const val DEFAULT_PORTAL_LIVE_VIEW_ROWS = 72
    private const val DEFAULT_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS = 48
    private const val DEFAULT_MENU_DISPATCH_REFILL_PER_SECOND = 12.0
    private const val DEFAULT_MENU_DISPATCH_BURST_TOKENS = 36.0
    private const val DEFAULT_MENU_DISPATCH_VIOLATION_DECAY_MS = 15_000L
    private const val DEFAULT_MENU_DISPATCH_BASE_COOLDOWN_MS = 500L
    private const val DEFAULT_MENU_DISPATCH_MAX_COOLDOWN_MS = 8_000L

    private const val DEFAULT_SPLINTER_MAX_ACTIVE_PER_OWNER = -1
    private const val DEFAULT_SPLINTER_MAX_EXECUTIONS_PER_TICK = 24
    private const val DEFAULT_SPLINTER_CASTER_ENABLED = true
    private const val DEFAULT_SPLINTER_MAX_LIFETIME_TICKS = 20L * 60L * 5L
    private const val DEFAULT_SPLINTER_MAX_NO_PROGRESS_TICKS = 20L * 30L
    private const val DEFAULT_SPLINTER_MAX_OVER_BUDGET_BREACHES = 3
    private const val DEFAULT_SPLINTER_USE_EXTERNALIZED_FOREACH_FRAME = true
    private const val EXTERNALIZED_FOREACH_INTERNAL_MAX_INNER_STEPS = 50_000_000
    private const val DEFAULT_SPLINTER_WRITE_ADVANCED_CONFIG = false

    private const val MIN_MENU_LOOP_WINDOW_MS = 200L
    private const val MAX_MENU_LOOP_WINDOW_MS = 10_000L
    private const val MIN_MENU_LOOP_TRIGGER_COUNT = 2
    private const val MAX_MENU_LOOP_TRIGGER_COUNT = 12
    private const val MIN_INTENT_RELAY_MAX_RANGE_BLOCKS = -1
    private const val MAX_INTENT_RELAY_MAX_RANGE_BLOCKS = 32
    private const val MIN_INTENT_RELAY_COOLDOWN_TICKS = 0
    private const val MAX_INTENT_RELAY_COOLDOWN_TICKS = 40
    private const val MIN_PORTAL_LIVE_VIEW_COLS = 12
    private const val MAX_PORTAL_LIVE_VIEW_COLS = 96
    private const val MIN_PORTAL_LIVE_VIEW_ROWS = 18
    private const val MAX_PORTAL_LIVE_VIEW_ROWS = 128
    private const val MIN_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS = 8
    private const val MAX_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS = 128
    private const val MIN_MENU_DISPATCH_REFILL_PER_SECOND = 1.0
    private const val MAX_MENU_DISPATCH_REFILL_PER_SECOND = 40.0
    private const val MIN_MENU_DISPATCH_BURST_TOKENS = 4.0
    private const val MAX_MENU_DISPATCH_BURST_TOKENS = 120.0
    private const val MIN_MENU_DISPATCH_VIOLATION_DECAY_MS = 1_000L
    private const val MAX_MENU_DISPATCH_VIOLATION_DECAY_MS = 120_000L
    private const val MIN_MENU_DISPATCH_BASE_COOLDOWN_MS = 100L
    private const val MAX_MENU_DISPATCH_BASE_COOLDOWN_MS = 5_000L
    private const val MIN_MENU_DISPATCH_MAX_COOLDOWN_MS = 250L
    private const val MAX_MENU_DISPATCH_MAX_COOLDOWN_MS = 60_000L

    private const val MIN_SPLINTER_MAX_ACTIVE_PER_OWNER = -1
    private const val MAX_SPLINTER_MAX_ACTIVE_PER_OWNER = 4096
    private const val MIN_SPLINTER_MAX_EXECUTIONS_PER_TICK = 1
    private const val MAX_SPLINTER_MAX_EXECUTIONS_PER_TICK = 256
    private const val MIN_SPLINTER_MAX_LIFETIME_TICKS = 20L
    private const val MAX_SPLINTER_MAX_LIFETIME_TICKS = 20L * 60L * 60L
    private const val MIN_SPLINTER_MAX_NO_PROGRESS_TICKS = 20L
    private const val MAX_SPLINTER_MAX_NO_PROGRESS_TICKS = 20L * 60L * 30L
    private const val MIN_SPLINTER_MAX_OVER_BUDGET_BREACHES = 1
    private const val MAX_SPLINTER_MAX_OVER_BUDGET_BREACHES = 64

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath = FabricLoader.getInstance().configDir.resolve("manifestation.json")

    @Volatile
    private var menuLoopWindowMs: Long = DEFAULT_MENU_LOOP_WINDOW_MS

    @Volatile
    private var menuLoopTriggerCount: Int = DEFAULT_MENU_LOOP_TRIGGER_COUNT

    @Volatile
    private var intentRelayMaxRangeBlocks: Int = DEFAULT_INTENT_RELAY_MAX_RANGE_BLOCKS

    @Volatile
    private var intentRelayCooldownTicks: Int = DEFAULT_INTENT_RELAY_COOLDOWN_TICKS

    @Volatile
    private var intentRelayStepTriggerEnabled: Boolean = DEFAULT_INTENT_RELAY_STEP_TRIGGER_ENABLED

    @Volatile
    private var portalLiveViewEnabled: Boolean = DEFAULT_PORTAL_LIVE_VIEW_ENABLED

    @Volatile
    private var portalLiveViewCols: Int = DEFAULT_PORTAL_LIVE_VIEW_COLS

    @Volatile
    private var portalLiveViewRows: Int = DEFAULT_PORTAL_LIVE_VIEW_ROWS

    @Volatile
    private var portalLiveViewDistanceBlocks: Int = DEFAULT_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS

    @Volatile
    private var menuDispatchRefillPerSecond: Double = DEFAULT_MENU_DISPATCH_REFILL_PER_SECOND

    @Volatile
    private var menuDispatchBurstTokens: Double = DEFAULT_MENU_DISPATCH_BURST_TOKENS

    @Volatile
    private var menuDispatchViolationDecayMs: Long = DEFAULT_MENU_DISPATCH_VIOLATION_DECAY_MS

    @Volatile
    private var menuDispatchBaseCooldownMs: Long = DEFAULT_MENU_DISPATCH_BASE_COOLDOWN_MS

    @Volatile
    private var menuDispatchMaxCooldownMs: Long = DEFAULT_MENU_DISPATCH_MAX_COOLDOWN_MS

    @Volatile
    private var splinterMaxActivePerOwner: Int = DEFAULT_SPLINTER_MAX_ACTIVE_PER_OWNER

    @Volatile
    private var splinterMaxExecutionsPerTick: Int = DEFAULT_SPLINTER_MAX_EXECUTIONS_PER_TICK

    @Volatile
    private var splinterCasterEnabled: Boolean = DEFAULT_SPLINTER_CASTER_ENABLED

    @Volatile
    private var splinterMaxLifetimeTicks: Long = DEFAULT_SPLINTER_MAX_LIFETIME_TICKS

    @Volatile
    private var splinterMaxNoProgressTicks: Long = DEFAULT_SPLINTER_MAX_NO_PROGRESS_TICKS

    @Volatile
    private var splinterMaxOverBudgetBreaches: Int = DEFAULT_SPLINTER_MAX_OVER_BUDGET_BREACHES

    @Volatile
    private var splinterUseExternalizedForEachFrame: Boolean = DEFAULT_SPLINTER_USE_EXTERNALIZED_FOREACH_FRAME

    @Volatile
    private var splinterPerformancePreset: SplinterPerformancePreset = SplinterPerformancePreset.SAFE

    @Volatile
    private var splinterAdvanced: SplinterAdvancedConfig = SplinterPerformanceTuning.safeAdvancedDefaults()

    @Volatile
    private var splinterWriteAdvancedConfig: Boolean = DEFAULT_SPLINTER_WRITE_ADVANCED_CONFIG

    @Volatile
    private var resolvedSplinterTuning: SplinterResolvedTuning = SplinterPerformanceTuning.resolveTuning(
        SplinterPerformancePreset.SAFE,
        SplinterPerformanceTuning.safeAdvancedDefaults()
    )

    @Volatile
    private var loggedResolvedTuning: Boolean = false

    fun load() {
        val loaded = readOrNull()
        val effective = sanitize(loaded)

        menuLoopWindowMs = effective.raw.menuOpenLoopWindowMs
        menuLoopTriggerCount = effective.raw.menuOpenLoopTriggerCount
        intentRelayMaxRangeBlocks = effective.raw.intentRelayMaxRangeBlocks
        intentRelayCooldownTicks = effective.raw.intentRelayCooldownTicks
        intentRelayStepTriggerEnabled = effective.raw.intentRelayStepTriggerEnabled
        portalLiveViewEnabled = effective.raw.portalLiveViewEnabled
        portalLiveViewCols = effective.raw.portalLiveViewCols
        portalLiveViewRows = effective.raw.portalLiveViewRows
        portalLiveViewDistanceBlocks = effective.raw.portalLiveViewDistanceBlocks
        menuDispatchRefillPerSecond = effective.raw.menuDispatchRefillPerSecond
        menuDispatchBurstTokens = effective.raw.menuDispatchBurstTokens
        menuDispatchViolationDecayMs = effective.raw.menuDispatchViolationDecayMs
        menuDispatchBaseCooldownMs = effective.raw.menuDispatchBaseCooldownMs
        menuDispatchMaxCooldownMs = effective.raw.menuDispatchMaxCooldownMs

        splinterMaxActivePerOwner = effective.raw.splinterMaxActivePerOwner
        splinterMaxExecutionsPerTick = effective.raw.splinterMaxExecutionsPerTick
        splinterCasterEnabled = effective.raw.splinterCasterEnabled
        splinterMaxLifetimeTicks = effective.raw.splinterMaxLifetimeTicks
        splinterMaxNoProgressTicks = effective.raw.splinterMaxNoProgressTicks
        splinterMaxOverBudgetBreaches = effective.raw.splinterMaxOverBudgetBreaches
        splinterUseExternalizedForEachFrame = effective.raw.splinterUseExternalizedForEachFrame

        splinterPerformancePreset = effective.preset
        splinterAdvanced = effective.advanced
        splinterWriteAdvancedConfig = effective.raw.splinterWriteAdvancedConfig
        resolvedSplinterTuning = effective.resolvedTuning
        loggedResolvedTuning = false

        if (loaded == null || effective.shouldRewrite || loaded.raw != effective.raw) {
            write(effective)
        }

        Manifestation.LOGGER.info(
            "Manifestation config loaded: splinterPerformancePreset={}, splinterAdvancedEnabled={}, splinterWriteAdvancedConfig={}",
            splinterPerformancePreset,
            splinterAdvanced.enabled,
            splinterWriteAdvancedConfig
        )
    }

    fun maybeLogResolvedSplinterTuningOnce() {
        if (loggedResolvedTuning) {
            return
        }
        loggedResolvedTuning = true

        val tuning = resolvedSplinterTuning
        Manifestation.LOGGER.info(
            "Manifestation splinter tuning: preset={}, globalBudgetMicrosPerTick={}, opsPerSlice={}, sliceBudgetMicros={}, maxSlicesPerTick={}, maxRecordScansPerTick={}, foreachChunk={}, safeInlineCap={}",
            splinterPerformancePreset,
            tuning.globalBudgetMicrosPerTick,
            tuning.opsPerSlice,
            tuning.sliceBudgetMicros,
            tuning.maxSlicesPerTick,
            tuning.maxRecordScansPerTick,
            tuning.largeForeachChunkExecutionSize,
            tuning.safeInlineForeachRemainingCap
        )
    }

    fun menuOpenLoopWindowMs(): Long = menuLoopWindowMs

    fun menuOpenLoopTriggerCount(): Int = menuLoopTriggerCount

    fun intentRelayMaxRangeBlocks(): Int = intentRelayMaxRangeBlocks

    fun intentRelayCooldownTicks(): Int = intentRelayCooldownTicks

    fun intentRelayStepTriggerEnabled(): Boolean = intentRelayStepTriggerEnabled

    fun portalLiveViewEnabled(): Boolean = portalLiveViewEnabled

    fun portalLiveViewCols(): Int = portalLiveViewCols

    fun portalLiveViewRows(): Int = portalLiveViewRows

    fun portalLiveViewDistanceBlocks(): Int = portalLiveViewDistanceBlocks

    fun menuDispatchRefillPerSecond(): Double = menuDispatchRefillPerSecond

    fun menuDispatchBurstTokens(): Double = menuDispatchBurstTokens

    fun menuDispatchViolationDecayMs(): Long = menuDispatchViolationDecayMs

    fun menuDispatchBaseCooldownMs(): Long = menuDispatchBaseCooldownMs

    fun menuDispatchMaxCooldownMs(): Long = menuDispatchMaxCooldownMs

    fun splinterMaxActivePerOwner(): Int = splinterMaxActivePerOwner

    fun splinterMaxExecutionsPerTick(): Int = splinterMaxExecutionsPerTick

    fun splinterMaxRecordScansPerTick(): Int = resolvedSplinterTuning.maxRecordScansPerTick

    fun splinterCasterEnabled(): Boolean = splinterCasterEnabled

    fun splinterPerformancePreset(): SplinterPerformancePreset = splinterPerformancePreset

    fun resolvedSplinterTuning(): SplinterResolvedTuning = resolvedSplinterTuning

    fun splinterGlobalBudgetMicrosPerTick(): Long = resolvedSplinterTuning.globalBudgetMicrosPerTick

    fun splinterOpsPerSlice(): Int = resolvedSplinterTuning.opsPerSlice

    fun splinterSliceBudgetMicros(): Long = resolvedSplinterTuning.sliceBudgetMicros

    fun splinterMaxSlicesPerTick(): Int = resolvedSplinterTuning.maxSlicesPerTick

    fun splinterEmergencySliceMillis(): Long = resolvedSplinterTuning.emergencySliceMillis

    fun splinterMaxLifetimeTicks(): Long = splinterMaxLifetimeTicks

    fun splinterMaxNoProgressTicks(): Long = splinterMaxNoProgressTicks

    fun splinterMaxTotalWorkUnits(): Long = resolvedSplinterTuning.maxTotalWorkUnits

    @Deprecated("Use splinterMaxTotalWorkUnits")
    fun splinterMaxTotalOps(): Long = splinterMaxTotalWorkUnits()

    fun splinterMaxOverBudgetBreaches(): Int = splinterMaxOverBudgetBreaches

    fun splinterDebugSliceTelemetry(): Boolean = resolvedSplinterTuning.debugSliceTelemetry

    fun splinterLargeListChunkSize(): Int = resolvedSplinterTuning.largeListChunkSize

    fun splinterLargeForeachChunkExecutionSize(): Int = resolvedSplinterTuning.largeForeachChunkExecutionSize

    fun splinterSafeInlineForeachRemainingCap(): Int = resolvedSplinterTuning.safeInlineForeachRemainingCap

    fun splinterUseExternalizedForEachFrame(): Boolean = splinterUseExternalizedForEachFrame

    @Deprecated("Externalized foreach chunks are atomic; this is an internal emergency guard only")
    fun splinterExternalizedForEachFrameInnerStepsPerEvaluate(): Int {
        return EXTERNALIZED_FOREACH_INTERNAL_MAX_INNER_STEPS
    }

    private fun readOrNull(): LoadedConfig? {
        if (!Files.exists(configPath)) {
            return null
        }

        return try {
            val text = Files.readString(configPath, StandardCharsets.UTF_8)
            val parsed = JsonParser.parseString(text)
            if (!parsed.isJsonObject) {
                Manifestation.LOGGER.warn(
                    "Manifestation: config at {} is not a JSON object. Using defaults.",
                    configPath
                )
                return null
            }

            val root = parsed.asJsonObject
            val raw = gson.fromJson(root, RawConfig::class.java)
            LoadedConfig(raw, root.keySet())
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn(
                "Manifestation: failed to read config at {}. Using defaults.",
                configPath,
                t
            )
            null
        }
    }

    private fun write(config: SanitizedConfig) {
        try {
            Files.createDirectories(configPath.parent)
            Files.newBufferedWriter(
                configPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { writer ->
                gson.toJson(config.toWritableConfig(), writer)
            }
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn("Manifestation: failed to write config at {}", configPath, t)
        }
    }

    private fun sanitize(loaded: LoadedConfig?): SanitizedConfig {
        val raw = loaded?.raw ?: RawConfig()
        val presentKeys = loaded?.presentKeys ?: emptySet()

        val presetProvided = "splinterPerformancePreset" in presentKeys
        val legacyAdvancedTopLevelPresent =
            SplinterPerformanceTuning.detectLegacyAdvancedTopLevelKeys(presentKeys)

        val resolvedPreset = SplinterPerformanceTuning.resolvePreset(
            presetRaw = raw.splinterPerformancePreset,
            presetProvided = presetProvided,
            hasLegacyTopLevelAdvancedKeys = legacyAdvancedTopLevelPresent,
            defaultPreset = SplinterPerformancePreset.SAFE
        )

        val inferredCustomFromLegacy = !presetProvided && legacyAdvancedTopLevelPresent
        val presetFallbackUsed = presetProvided && SplinterPerformancePreset.parseOrNull(raw.splinterPerformancePreset) == null

        val legacyAdvanced = SplinterPerformanceTuning.fromLegacyTopLevel(
            enabled = inferredCustomFromLegacy,
            globalBudgetMicrosPerTick = raw.splinterGlobalBudgetMicrosPerTick.coerceAtLeast(1L),
            opsPerSlice = raw.splinterOpsPerSlice.coerceAtLeast(1),
            sliceBudgetMicros = raw.splinterSliceBudgetMicros.coerceAtLeast(1L),
            maxSlicesPerTick = raw.splinterMaxSlicesPerTick.coerceAtLeast(1),
            maxRecordScansPerTick = raw.splinterMaxRecordScansPerTick.coerceAtLeast(1),
            emergencySliceMillis = raw.splinterEmergencySliceMillis.coerceAtLeast(1L),
            maxTotalWorkUnits = (raw.splinterMaxTotalOps ?: raw.splinterMaxTotalWorkUnits).coerceAtLeast(1L),
            largeListChunkSize = raw.splinterLargeListChunkSize.coerceAtLeast(1),
            largeForeachChunkExecutionSize = raw.splinterLargeForeachChunkExecutionSize.coerceAtLeast(1),
            safeInlineForeachRemainingCap = raw.splinterSafeInlineForeachRemainingCap.coerceAtLeast(1),
            debugSliceTelemetry = raw.splinterDebugSliceTelemetry
        )

        val nestedAdvancedRaw = raw.splinterAdvanced
            ?: if (inferredCustomFromLegacy) {
                SplinterAdvancedRawConfig.from(legacyAdvanced)
            } else {
                SplinterAdvancedRawConfig.from(SplinterPerformanceTuning.safeAdvancedDefaults())
            }
        val nestedAdvanced = sanitizeAdvanced(nestedAdvancedRaw)
        val effectiveAdvanced = if (inferredCustomFromLegacy && raw.splinterAdvanced == null) {
            legacyAdvanced.copy(enabled = true)
        } else {
            nestedAdvanced
        }
        val resolvedTuning = SplinterPerformanceTuning.resolveTuning(resolvedPreset, effectiveAdvanced)

        val sanitized = raw.copy(
            menuOpenLoopWindowMs = raw.menuOpenLoopWindowMs.coerceIn(
                MIN_MENU_LOOP_WINDOW_MS,
                MAX_MENU_LOOP_WINDOW_MS
            ),
            menuOpenLoopTriggerCount = raw.menuOpenLoopTriggerCount.coerceIn(
                MIN_MENU_LOOP_TRIGGER_COUNT,
                MAX_MENU_LOOP_TRIGGER_COUNT
            ),
            intentRelayMaxRangeBlocks = raw.intentRelayMaxRangeBlocks.coerceIn(
                MIN_INTENT_RELAY_MAX_RANGE_BLOCKS,
                MAX_INTENT_RELAY_MAX_RANGE_BLOCKS
            ),
            intentRelayCooldownTicks = raw.intentRelayCooldownTicks.coerceIn(
                MIN_INTENT_RELAY_COOLDOWN_TICKS,
                MAX_INTENT_RELAY_COOLDOWN_TICKS
            ),
            portalLiveViewCols = raw.portalLiveViewCols.coerceIn(
                MIN_PORTAL_LIVE_VIEW_COLS,
                MAX_PORTAL_LIVE_VIEW_COLS
            ),
            portalLiveViewRows = raw.portalLiveViewRows.coerceIn(
                MIN_PORTAL_LIVE_VIEW_ROWS,
                MAX_PORTAL_LIVE_VIEW_ROWS
            ),
            portalLiveViewDistanceBlocks = raw.portalLiveViewDistanceBlocks.coerceIn(
                MIN_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS,
                MAX_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS
            ),
            menuDispatchRefillPerSecond = raw.menuDispatchRefillPerSecond.coerceIn(
                MIN_MENU_DISPATCH_REFILL_PER_SECOND,
                MAX_MENU_DISPATCH_REFILL_PER_SECOND
            ),
            menuDispatchBurstTokens = raw.menuDispatchBurstTokens.coerceIn(
                MIN_MENU_DISPATCH_BURST_TOKENS,
                MAX_MENU_DISPATCH_BURST_TOKENS
            ),
            menuDispatchViolationDecayMs = raw.menuDispatchViolationDecayMs.coerceIn(
                MIN_MENU_DISPATCH_VIOLATION_DECAY_MS,
                MAX_MENU_DISPATCH_VIOLATION_DECAY_MS
            ),
            menuDispatchBaseCooldownMs = raw.menuDispatchBaseCooldownMs.coerceIn(
                MIN_MENU_DISPATCH_BASE_COOLDOWN_MS,
                MAX_MENU_DISPATCH_BASE_COOLDOWN_MS
            ),
            menuDispatchMaxCooldownMs = raw.menuDispatchMaxCooldownMs.coerceIn(
                MIN_MENU_DISPATCH_MAX_COOLDOWN_MS,
                MAX_MENU_DISPATCH_MAX_COOLDOWN_MS
            ),
            splinterMaxActivePerOwner = raw.splinterMaxActivePerOwner.coerceIn(
                MIN_SPLINTER_MAX_ACTIVE_PER_OWNER,
                MAX_SPLINTER_MAX_ACTIVE_PER_OWNER
            ),
            splinterMaxExecutionsPerTick = raw.splinterMaxExecutionsPerTick.coerceIn(
                MIN_SPLINTER_MAX_EXECUTIONS_PER_TICK,
                MAX_SPLINTER_MAX_EXECUTIONS_PER_TICK
            ),
            splinterMaxLifetimeTicks = raw.splinterMaxLifetimeTicks.coerceIn(
                MIN_SPLINTER_MAX_LIFETIME_TICKS,
                MAX_SPLINTER_MAX_LIFETIME_TICKS
            ),
            splinterMaxNoProgressTicks = raw.splinterMaxNoProgressTicks.coerceIn(
                MIN_SPLINTER_MAX_NO_PROGRESS_TICKS,
                MAX_SPLINTER_MAX_NO_PROGRESS_TICKS
            ),
            splinterMaxOverBudgetBreaches = raw.splinterMaxOverBudgetBreaches.coerceIn(
                MIN_SPLINTER_MAX_OVER_BUDGET_BREACHES,
                MAX_SPLINTER_MAX_OVER_BUDGET_BREACHES
            ),
            splinterGlobalBudgetMicrosPerTick = effectiveAdvanced.globalBudgetMicrosPerTick,
            splinterOpsPerSlice = effectiveAdvanced.opsPerSlice,
            splinterSliceBudgetMicros = effectiveAdvanced.sliceBudgetMicros,
            splinterMaxSlicesPerTick = effectiveAdvanced.maxSlicesPerTick,
            splinterMaxRecordScansPerTick = effectiveAdvanced.maxRecordScansPerTick,
            splinterEmergencySliceMillis = effectiveAdvanced.emergencySliceMillis,
            splinterMaxTotalWorkUnits = effectiveAdvanced.maxTotalWorkUnits,
            splinterMaxTotalOps = null,
            splinterDebugSliceTelemetry = effectiveAdvanced.debugSliceTelemetry,
            splinterLargeListChunkSize = effectiveAdvanced.largeListChunkSize,
            splinterLargeForeachChunkExecutionSize = effectiveAdvanced.largeForeachChunkExecutionSize,
            splinterSafeInlineForeachRemainingCap = effectiveAdvanced.safeInlineForeachRemainingCap,
            splinterPerformancePreset = resolvedPreset.name.lowercase(),
            splinterAdvanced = SplinterAdvancedRawConfig.from(effectiveAdvanced),
            splinterWriteAdvancedConfig = raw.splinterWriteAdvancedConfig
        ).let { cleaned ->
            if (cleaned.menuDispatchMaxCooldownMs < cleaned.menuDispatchBaseCooldownMs) {
                cleaned.copy(menuDispatchMaxCooldownMs = cleaned.menuDispatchBaseCooldownMs)
            } else {
                cleaned
            }
        }

        return SanitizedConfig(
            raw = sanitized,
            preset = resolvedPreset,
            advanced = effectiveAdvanced,
            resolvedTuning = resolvedTuning,
            shouldRewrite = inferredCustomFromLegacy || presetFallbackUsed || legacyAdvancedTopLevelPresent
        )
    }

    private fun sanitizeAdvanced(raw: SplinterAdvancedRawConfig): SplinterAdvancedConfig {
        return SplinterAdvancedConfig(
            enabled = raw.enabled,
            globalBudgetMicrosPerTick = raw.globalBudgetMicrosPerTick.coerceAtLeast(1L),
            opsPerSlice = raw.opsPerSlice.coerceAtLeast(1),
            sliceBudgetMicros = raw.sliceBudgetMicros.coerceAtLeast(1L),
            maxSlicesPerTick = raw.maxSlicesPerTick.coerceAtLeast(1),
            maxRecordScansPerTick = raw.maxRecordScansPerTick.coerceAtLeast(1),
            emergencySliceMillis = raw.emergencySliceMillis.coerceAtLeast(1L),
            maxTotalWorkUnits = raw.maxTotalWorkUnits.coerceAtLeast(1L),
            largeListChunkSize = raw.largeListChunkSize.coerceAtLeast(1),
            largeForeachChunkExecutionSize = raw.largeForeachChunkExecutionSize.coerceAtLeast(1),
            safeInlineForeachRemainingCap = raw.safeInlineForeachRemainingCap.coerceAtLeast(1),
            debugSliceTelemetry = raw.debugSliceTelemetry
        )
    }

    private fun SanitizedConfig.toWritableConfig(): WritableConfig {
        val includeAdvancedValues = preset == SplinterPerformancePreset.CUSTOM || raw.splinterWriteAdvancedConfig
        val advancedForWrite = if (includeAdvancedValues) {
            WritableSplinterAdvancedConfig.from(advanced)
        } else {
            WritableSplinterAdvancedConfig(enabled = false)
        }

        return WritableConfig(
            menuOpenLoopWindowMs = raw.menuOpenLoopWindowMs,
            menuOpenLoopTriggerCount = raw.menuOpenLoopTriggerCount,
            intentRelayMaxRangeBlocks = raw.intentRelayMaxRangeBlocks,
            intentRelayCooldownTicks = raw.intentRelayCooldownTicks,
            intentRelayStepTriggerEnabled = raw.intentRelayStepTriggerEnabled,
            portalLiveViewEnabled = raw.portalLiveViewEnabled,
            portalLiveViewCols = raw.portalLiveViewCols,
            portalLiveViewRows = raw.portalLiveViewRows,
            portalLiveViewDistanceBlocks = raw.portalLiveViewDistanceBlocks,
            menuDispatchRefillPerSecond = raw.menuDispatchRefillPerSecond,
            menuDispatchBurstTokens = raw.menuDispatchBurstTokens,
            menuDispatchViolationDecayMs = raw.menuDispatchViolationDecayMs,
            menuDispatchBaseCooldownMs = raw.menuDispatchBaseCooldownMs,
            menuDispatchMaxCooldownMs = raw.menuDispatchMaxCooldownMs,
            splinterCasterEnabled = raw.splinterCasterEnabled,
            splinterMaxActivePerOwner = raw.splinterMaxActivePerOwner,
            splinterPerformancePreset = preset.name.lowercase(),
            splinterUseExternalizedForEachFrame = raw.splinterUseExternalizedForEachFrame,
            splinterAdvanced = advancedForWrite,
            splinterWriteAdvancedConfig = if (raw.splinterWriteAdvancedConfig) true else null
        )
    }

    private data class LoadedConfig(
        val raw: RawConfig,
        val presentKeys: Set<String>
    )

    private data class SanitizedConfig(
        val raw: RawConfig,
        val preset: SplinterPerformancePreset,
        val advanced: SplinterAdvancedConfig,
        val resolvedTuning: SplinterResolvedTuning,
        val shouldRewrite: Boolean
    )

    private data class RawConfig(
        var menuOpenLoopWindowMs: Long = DEFAULT_MENU_LOOP_WINDOW_MS,
        var menuOpenLoopTriggerCount: Int = DEFAULT_MENU_LOOP_TRIGGER_COUNT,
        var intentRelayMaxRangeBlocks: Int = DEFAULT_INTENT_RELAY_MAX_RANGE_BLOCKS,
        var intentRelayCooldownTicks: Int = DEFAULT_INTENT_RELAY_COOLDOWN_TICKS,
        var intentRelayStepTriggerEnabled: Boolean = DEFAULT_INTENT_RELAY_STEP_TRIGGER_ENABLED,
        var portalLiveViewEnabled: Boolean = DEFAULT_PORTAL_LIVE_VIEW_ENABLED,
        var portalLiveViewCols: Int = DEFAULT_PORTAL_LIVE_VIEW_COLS,
        var portalLiveViewRows: Int = DEFAULT_PORTAL_LIVE_VIEW_ROWS,
        var portalLiveViewDistanceBlocks: Int = DEFAULT_PORTAL_LIVE_VIEW_DISTANCE_BLOCKS,
        var menuDispatchRefillPerSecond: Double = DEFAULT_MENU_DISPATCH_REFILL_PER_SECOND,
        var menuDispatchBurstTokens: Double = DEFAULT_MENU_DISPATCH_BURST_TOKENS,
        var menuDispatchViolationDecayMs: Long = DEFAULT_MENU_DISPATCH_VIOLATION_DECAY_MS,
        var menuDispatchBaseCooldownMs: Long = DEFAULT_MENU_DISPATCH_BASE_COOLDOWN_MS,
        var menuDispatchMaxCooldownMs: Long = DEFAULT_MENU_DISPATCH_MAX_COOLDOWN_MS,
        var splinterMaxActivePerOwner: Int = DEFAULT_SPLINTER_MAX_ACTIVE_PER_OWNER,
        var splinterMaxExecutionsPerTick: Int = DEFAULT_SPLINTER_MAX_EXECUTIONS_PER_TICK,
        var splinterCasterEnabled: Boolean = DEFAULT_SPLINTER_CASTER_ENABLED,
        var splinterMaxLifetimeTicks: Long = DEFAULT_SPLINTER_MAX_LIFETIME_TICKS,
        var splinterMaxNoProgressTicks: Long = DEFAULT_SPLINTER_MAX_NO_PROGRESS_TICKS,
        var splinterMaxOverBudgetBreaches: Int = DEFAULT_SPLINTER_MAX_OVER_BUDGET_BREACHES,
        var splinterUseExternalizedForEachFrame: Boolean = DEFAULT_SPLINTER_USE_EXTERNALIZED_FOREACH_FRAME,
        var splinterGlobalBudgetMicrosPerTick: Long = 45_000L,
        var splinterOpsPerSlice: Int = 10_000,
        var splinterSliceBudgetMicros: Long = 45_000L,
        var splinterMaxSlicesPerTick: Int = 8,
        var splinterMaxRecordScansPerTick: Int = 512,
        var splinterEmergencySliceMillis: Long = 100L,
        var splinterMaxTotalWorkUnits: Long = 1_000_000L,
        var splinterMaxTotalOps: Long? = null,
        var splinterDebugSliceTelemetry: Boolean = false,
        var splinterLargeListChunkSize: Int = 4096,
        var splinterLargeForeachChunkExecutionSize: Int = 4096,
        var splinterSafeInlineForeachRemainingCap: Int = 4096,
        @Deprecated("Legacy key, ignored for scheduling")
        var splinterExternalizedForEachFrameInnerStepsPerEvaluate: Int = 1,
        var splinterPerformancePreset: String? = null,
        var splinterAdvanced: SplinterAdvancedRawConfig? = null,
        var splinterWriteAdvancedConfig: Boolean = DEFAULT_SPLINTER_WRITE_ADVANCED_CONFIG
    )

    private data class SplinterAdvancedRawConfig(
        var enabled: Boolean = false,
        var globalBudgetMicrosPerTick: Long = 45_000L,
        var opsPerSlice: Int = 10_000,
        var sliceBudgetMicros: Long = 45_000L,
        var maxSlicesPerTick: Int = 8,
        var maxRecordScansPerTick: Int = 512,
        var emergencySliceMillis: Long = 100L,
        var maxTotalWorkUnits: Long = 1_000_000L,
        var largeListChunkSize: Int = 4096,
        var largeForeachChunkExecutionSize: Int = 4096,
        var safeInlineForeachRemainingCap: Int = 4096,
        var debugSliceTelemetry: Boolean = false
    ) {
        companion object {
            fun from(config: SplinterAdvancedConfig): SplinterAdvancedRawConfig {
                return SplinterAdvancedRawConfig(
                    enabled = config.enabled,
                    globalBudgetMicrosPerTick = config.globalBudgetMicrosPerTick,
                    opsPerSlice = config.opsPerSlice,
                    sliceBudgetMicros = config.sliceBudgetMicros,
                    maxSlicesPerTick = config.maxSlicesPerTick,
                    maxRecordScansPerTick = config.maxRecordScansPerTick,
                    emergencySliceMillis = config.emergencySliceMillis,
                    maxTotalWorkUnits = config.maxTotalWorkUnits,
                    largeListChunkSize = config.largeListChunkSize,
                    largeForeachChunkExecutionSize = config.largeForeachChunkExecutionSize,
                    safeInlineForeachRemainingCap = config.safeInlineForeachRemainingCap,
                    debugSliceTelemetry = config.debugSliceTelemetry
                )
            }
        }
    }

    private data class WritableConfig(
        val menuOpenLoopWindowMs: Long,
        val menuOpenLoopTriggerCount: Int,
        val intentRelayMaxRangeBlocks: Int,
        val intentRelayCooldownTicks: Int,
        val intentRelayStepTriggerEnabled: Boolean,
        val portalLiveViewEnabled: Boolean,
        val portalLiveViewCols: Int,
        val portalLiveViewRows: Int,
        val portalLiveViewDistanceBlocks: Int,
        val menuDispatchRefillPerSecond: Double,
        val menuDispatchBurstTokens: Double,
        val menuDispatchViolationDecayMs: Long,
        val menuDispatchBaseCooldownMs: Long,
        val menuDispatchMaxCooldownMs: Long,
        val splinterCasterEnabled: Boolean,
        val splinterMaxActivePerOwner: Int,
        val splinterPerformancePreset: String,
        val splinterUseExternalizedForEachFrame: Boolean,
        val splinterAdvanced: WritableSplinterAdvancedConfig,
        val splinterWriteAdvancedConfig: Boolean? = null
    )

    private data class WritableSplinterAdvancedConfig(
        val enabled: Boolean,
        val globalBudgetMicrosPerTick: Long? = null,
        val opsPerSlice: Int? = null,
        val sliceBudgetMicros: Long? = null,
        val maxSlicesPerTick: Int? = null,
        val maxRecordScansPerTick: Int? = null,
        val emergencySliceMillis: Long? = null,
        val maxTotalWorkUnits: Long? = null,
        val largeListChunkSize: Int? = null,
        val largeForeachChunkExecutionSize: Int? = null,
        val safeInlineForeachRemainingCap: Int? = null,
        val debugSliceTelemetry: Boolean? = null
    ) {
        companion object {
            fun from(advanced: SplinterAdvancedConfig): WritableSplinterAdvancedConfig {
                return WritableSplinterAdvancedConfig(
                    enabled = advanced.enabled,
                    globalBudgetMicrosPerTick = advanced.globalBudgetMicrosPerTick,
                    opsPerSlice = advanced.opsPerSlice,
                    sliceBudgetMicros = advanced.sliceBudgetMicros,
                    maxSlicesPerTick = advanced.maxSlicesPerTick,
                    maxRecordScansPerTick = advanced.maxRecordScansPerTick,
                    emergencySliceMillis = advanced.emergencySliceMillis,
                    maxTotalWorkUnits = advanced.maxTotalWorkUnits,
                    largeListChunkSize = advanced.largeListChunkSize,
                    largeForeachChunkExecutionSize = advanced.largeForeachChunkExecutionSize,
                    safeInlineForeachRemainingCap = advanced.safeInlineForeachRemainingCap,
                    debugSliceTelemetry = advanced.debugSliceTelemetry
                )
            }
        }
    }
}
