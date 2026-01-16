package com.levelscraft7.catchindicator.mixin;

import com.levelscraft7.catchindicator.client.PokedexRefreshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.client.ClientPokedexManager", remap = false)
public class ClientPokedexManagerMixin {
    @Inject(method = "setSpeciesRecords(Ljava/util/Map;)V", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetSpeciesRecords(Map<?, ?> records, CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(this);
    }

    @Inject(method = "setRecords(Ljava/util/Map;)V", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetRecords(Map<?, ?> records, CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(this);
    }

    @Inject(method = "sync()V", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSync(CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(this);
    }

    @Inject(method = "applySync()V", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onApplySync(CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(this);
    }
}
