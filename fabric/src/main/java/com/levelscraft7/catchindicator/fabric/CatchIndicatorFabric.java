package com.levelscraft7.catchindicator.fabric;

import net.fabricmc.api.ModInitializer;
import com.levelscraft7.catchindicator.CatchIndicator;

public final class CatchIndicatorFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CatchIndicator.init();
    }
}
