package com.bluup.manifestation.server.splinter

import at.petrak.hexcasting.common.lib.hex.HexContinuationTypes
import com.bluup.manifestation.Manifestation
import net.minecraft.core.Registry

object ManifestationContinuationFrames {
    private const val EXTERNALIZED_FOREACH_PATH = "externalized_foreach"
    private var registered = false

    fun register() {
        if (registered) {
            return
        }

        Registry.register(
            HexContinuationTypes.REGISTRY,
            Manifestation.id(EXTERNALIZED_FOREACH_PATH),
            ManifestationExternalizedForEachFrame.TYPE
        )
        registered = true
    }
}
