package com.bluup.manifestation;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod-wide constants and logger. Referenced from both the client and server
 * initializers, so it lives in the common (unscoped) package.
 */
public final class Manifestation {
    public static final String MOD_ID = "manifestation";
    public static final Logger LOGGER = LoggerFactory.getLogger("Manifestation");

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private Manifestation() {
    }
}
