package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.api.storage.player.client.ClientInstancedPlayerData;
import com.levelscraft7.catchindicator.client.PokedexRefreshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.api.storage.player.client.ClientPokedexManager$Companion", remap = false)
public class ClientPokedexIncrementalMixin {

    @Inject(method = "runIncremental", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$afterIncremental(ClientInstancedPlayerData data, CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(data);
    }

    @Inject(method = "runAction", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$afterAction(ClientInstancedPlayerData data, CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(data);
    }
}
