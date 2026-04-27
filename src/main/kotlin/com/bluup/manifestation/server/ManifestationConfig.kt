package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import com.google.gson.GsonBuilder
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

    fun load() {
        val loaded = readOrNull()
        val effective = sanitize(loaded ?: RawConfig())

        menuLoopWindowMs = effective.menuOpenLoopWindowMs
        menuLoopTriggerCount = effective.menuOpenLoopTriggerCount
        intentRelayMaxRangeBlocks = effective.intentRelayMaxRangeBlocks
        intentRelayCooldownTicks = effective.intentRelayCooldownTicks
        intentRelayStepTriggerEnabled = effective.intentRelayStepTriggerEnabled
        portalLiveViewEnabled = effective.portalLiveViewEnabled
        portalLiveViewCols = effective.portalLiveViewCols
        portalLiveViewRows = effective.portalLiveViewRows
        portalLiveViewDistanceBlocks = effective.portalLiveViewDistanceBlocks
        menuDispatchRefillPerSecond = effective.menuDispatchRefillPerSecond
        menuDispatchBurstTokens = effective.menuDispatchBurstTokens
        menuDispatchViolationDecayMs = effective.menuDispatchViolationDecayMs
        menuDispatchBaseCooldownMs = effective.menuDispatchBaseCooldownMs
        menuDispatchMaxCooldownMs = effective.menuDispatchMaxCooldownMs

        if (loaded == null || loaded != effective) {
            write(effective)
        }

        Manifestation.LOGGER.info(
            "Manifestation config loaded: menuOpenLoopWindowMs={}, menuOpenLoopTriggerCount={}, " +
                "intentRelayMaxRangeBlocks={}, intentRelayCooldownTicks={}, intentRelayStepTriggerEnabled={}, " +
                "portalLiveViewEnabled={}, portalLiveViewCols={}, portalLiveViewRows={}, portalLiveViewDistanceBlocks={}, " +
                "menuDispatchRefillPerSecond={}, menuDispatchBurstTokens={}, menuDispatchViolationDecayMs={}, " +
                "menuDispatchBaseCooldownMs={}, menuDispatchMaxCooldownMs={}",
            menuLoopWindowMs,
            menuLoopTriggerCount,
            intentRelayMaxRangeBlocks,
            intentRelayCooldownTicks,
            intentRelayStepTriggerEnabled,
            portalLiveViewEnabled,
            portalLiveViewCols,
            portalLiveViewRows,
            portalLiveViewDistanceBlocks,
            menuDispatchRefillPerSecond,
            menuDispatchBurstTokens,
            menuDispatchViolationDecayMs,
            menuDispatchBaseCooldownMs,
            menuDispatchMaxCooldownMs
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

    private fun readOrNull(): RawConfig? {
        if (!Files.exists(configPath)) {
            return null
        }

        return try {
            Files.newBufferedReader(configPath, StandardCharsets.UTF_8).use { reader ->
                gson.fromJson(reader, RawConfig::class.java)
            }
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn("Manifestation: failed to read config at {}. Using defaults.", configPath, t)
            null
        }
    }

    private fun write(config: RawConfig) {
        try {
            Files.createDirectories(configPath.parent)
            Files.newBufferedWriter(
                configPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            ).use { writer ->
                gson.toJson(config, writer)
            }
        } catch (t: Throwable) {
            Manifestation.LOGGER.warn("Manifestation: failed to write config at {}", configPath, t)
        }
    }

    private fun sanitize(raw: RawConfig): RawConfig {
        return RawConfig(
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
            intentRelayStepTriggerEnabled = raw.intentRelayStepTriggerEnabled,
            portalLiveViewEnabled = raw.portalLiveViewEnabled,
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
            )
        ).let { sanitized ->
            if (sanitized.menuDispatchMaxCooldownMs < sanitized.menuDispatchBaseCooldownMs) {
                sanitized.copy(menuDispatchMaxCooldownMs = sanitized.menuDispatchBaseCooldownMs)
            } else {
                sanitized
            }
        }
    }

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
        var menuDispatchMaxCooldownMs: Long = DEFAULT_MENU_DISPATCH_MAX_COOLDOWN_MS
    )
}