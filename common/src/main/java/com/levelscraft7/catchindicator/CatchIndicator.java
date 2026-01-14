package com.levelscraft7.catchindicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal common entrypoint for a multi-loader (NeoForge + Fabric) mod.
 * Put shared init code here.
 */
public final class CatchIndicator {
    public static final String MOD_ID = "catchindicator";
    public static final Logger LOGGER = LoggerFactory.getLogger("CatchIndicator");

    private CatchIndicator() {}

    public static void init() {
        LOGGER.info("[{}] Common init OK", MOD_ID);
        // Register common content here (items, blocks, networking, etc.).
    }
}
