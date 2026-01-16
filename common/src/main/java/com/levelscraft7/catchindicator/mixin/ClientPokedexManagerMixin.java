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

    @Inject(method = "setSpeciesRecord", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetSpeciesRecord(Object key, Object record, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, record);
    }

    @Inject(method = "setRecord", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetRecord(Object key, Object record, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, record);
    }

    @Inject(method = "setRecords(Ljava/util/Map;)V", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetRecords(Map<?, ?> records, CallbackInfo ci) {
        PokedexRefreshManager.onPokedexSync(this);
    }

    @Inject(method = "updateSpeciesRecord", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onUpdateSpeciesRecord(Object key, Object record, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, record);
    }

    @Inject(method = "updateRecord", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onUpdateRecord(Object key, Object record, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, record);
    }

    @Inject(method = "setCaughtForms", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onSetCaughtForms(Object key, Object record, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, record);
    }

    @Inject(method = "addCaughtForm", at = @At("TAIL"), remap = false, require = 0)
    private void catchindicator$onAddCaughtForm(Object key, Object form, CallbackInfo ci) {
        PokedexRefreshManager.onRecordUpdate(this, key, null);
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
