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

    private const val MIN_MENU_LOOP_WINDOW_MS = 200L
    private const val MAX_MENU_LOOP_WINDOW_MS = 10_000L
    private const val MIN_MENU_LOOP_TRIGGER_COUNT = 2
    private const val MAX_MENU_LOOP_TRIGGER_COUNT = 12
    private const val MIN_INTENT_RELAY_MAX_RANGE_BLOCKS = -1
    private const val MAX_INTENT_RELAY_MAX_RANGE_BLOCKS = 32
    private const val MIN_INTENT_RELAY_COOLDOWN_TICKS = 0
    private const val MAX_INTENT_RELAY_COOLDOWN_TICKS = 40

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

    fun load() {
        val loaded = readOrNull()
        val effective = sanitize(loaded ?: RawConfig())

        menuLoopWindowMs = effective.menuOpenLoopWindowMs
        menuLoopTriggerCount = effective.menuOpenLoopTriggerCount
        intentRelayMaxRangeBlocks = effective.intentRelayMaxRangeBlocks
        intentRelayCooldownTicks = effective.intentRelayCooldownTicks
        intentRelayStepTriggerEnabled = effective.intentRelayStepTriggerEnabled

        if (loaded == null || loaded != effective) {
            write(effective)
        }

        Manifestation.LOGGER.info(
            "Manifestation config loaded: menuOpenLoopWindowMs={}, menuOpenLoopTriggerCount={}, " +
                "intentRelayMaxRangeBlocks={}, intentRelayCooldownTicks={}, intentRelayStepTriggerEnabled={}",
            menuLoopWindowMs,
            menuLoopTriggerCount,
            intentRelayMaxRangeBlocks,
            intentRelayCooldownTicks,
            intentRelayStepTriggerEnabled
        )
    }

    fun menuOpenLoopWindowMs(): Long = menuLoopWindowMs

    fun menuOpenLoopTriggerCount(): Int = menuLoopTriggerCount

    fun intentRelayMaxRangeBlocks(): Int = intentRelayMaxRangeBlocks

    fun intentRelayCooldownTicks(): Int = intentRelayCooldownTicks

    fun intentRelayStepTriggerEnabled(): Boolean = intentRelayStepTriggerEnabled

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
            intentRelayStepTriggerEnabled = raw.intentRelayStepTriggerEnabled
        )
    }

    private data class RawConfig(
        var menuOpenLoopWindowMs: Long = DEFAULT_MENU_LOOP_WINDOW_MS,
        var menuOpenLoopTriggerCount: Int = DEFAULT_MENU_LOOP_TRIGGER_COUNT,
        var intentRelayMaxRangeBlocks: Int = DEFAULT_INTENT_RELAY_MAX_RANGE_BLOCKS,
        var intentRelayCooldownTicks: Int = DEFAULT_INTENT_RELAY_COOLDOWN_TICKS,
        var intentRelayStepTriggerEnabled: Boolean = DEFAULT_INTENT_RELAY_STEP_TRIGGER_ENABLED
    )
}