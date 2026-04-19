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

    private const val MIN_MENU_LOOP_WINDOW_MS = 200L
    private const val MAX_MENU_LOOP_WINDOW_MS = 10_000L
    private const val MIN_MENU_LOOP_TRIGGER_COUNT = 2
    private const val MAX_MENU_LOOP_TRIGGER_COUNT = 12

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configPath = FabricLoader.getInstance().configDir.resolve("manifestation.json")

    @Volatile
    private var menuLoopWindowMs: Long = DEFAULT_MENU_LOOP_WINDOW_MS

    @Volatile
    private var menuLoopTriggerCount: Int = DEFAULT_MENU_LOOP_TRIGGER_COUNT

    fun load() {
        val loaded = readOrNull()
        val effective = sanitize(loaded ?: RawConfig())

        menuLoopWindowMs = effective.menuOpenLoopWindowMs
        menuLoopTriggerCount = effective.menuOpenLoopTriggerCount

        if (loaded == null || loaded != effective) {
            write(effective)
        }

        Manifestation.LOGGER.info(
            "Manifestation config loaded: menuOpenLoopWindowMs={}, menuOpenLoopTriggerCount={}",
            menuLoopWindowMs,
            menuLoopTriggerCount
        )
    }

    fun menuOpenLoopWindowMs(): Long = menuLoopWindowMs

    fun menuOpenLoopTriggerCount(): Int = menuLoopTriggerCount

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
            )
        )
    }

    private data class RawConfig(
        var menuOpenLoopWindowMs: Long = DEFAULT_MENU_LOOP_WINDOW_MS,
        var menuOpenLoopTriggerCount: Int = DEFAULT_MENU_LOOP_TRIGGER_COUNT
    )
}