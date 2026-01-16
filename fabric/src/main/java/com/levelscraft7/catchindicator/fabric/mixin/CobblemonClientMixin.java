package com.levelscraft7.catchindicator.fabric.mixin;

import com.levelscraft7.catchindicator.client.PokedexRefreshManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cobblemon 1.7 syncs the player's Pokédex via instanced player data.
 * When the client receives updated Pokédex data, Cobblemon sets it on CobblemonClient.
 *
 * We hook that moment to update our caught cache and force a nametag refresh so wild entities
 * immediately show the caught icon without requiring a "send out".
 */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.client.CobblemonClient", remap = false)
public class CobblemonClientMixin {

    @Inject(method = "setClientPokedexData", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onClientPokedexDataSet(Object newData, CallbackInfo ci) {
        // newData is com.cobblemon.mod.common.api.storage.player.client.ClientPokedexManager
        PokedexRefreshManager.onPokedexSync(newData);
    }
}
