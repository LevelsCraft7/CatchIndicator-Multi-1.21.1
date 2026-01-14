package com.levelscraft7.catchindicator.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import com.levelscraft7.catchindicator.CatchIndicator;

@Mod(CatchIndicator.MOD_ID)
public final class CatchIndicatorNeoForge {
    public CatchIndicatorNeoForge(IEventBus modEventBus) {
        CatchIndicator.init();
        // Register NeoForge-specific things via modEventBus if needed.
    }
}
