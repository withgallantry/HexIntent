package com.bluup.manifestation.server

import com.bluup.manifestation.Manifestation
import net.fabricmc.loader.api.FabricLoader

/**
 * Centralized compatibility feature flags for optional addon integrations.
 */
object InteropFlags {
    @JvmField
    val HEXICAL_INTEROP: Boolean = FabricLoader.getInstance().isModLoaded("hexical")

    fun logDetectedInterop() {
        if (HEXICAL_INTEROP) {
            Manifestation.LOGGER.info("Manifestation interop: Hexical detected, enabling Hexical compatibility hooks.")
        } else {
            Manifestation.LOGGER.info("Manifestation interop: Hexical not detected, Hexical compatibility remains inactive.")
        }
    }
}
