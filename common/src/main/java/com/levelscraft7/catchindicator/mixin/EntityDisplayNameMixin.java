package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class EntityDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void catchindicator$decorateDisplayName(CallbackInfoReturnable<Component> cir) {
        Object self = this;

        if (!(self instanceof PokemonEntity pokemonEntity)) return;

        // Important : on délègue à ta logique existante en appelant getName()
        // car PokemonEntityNameMixin décore déjà getName().
        // getDisplayName doit renvoyer le même texte décoré.
        Component decorated = pokemonEntity.getName();
        if (decorated != null) {
            cir.setReturnValue(decorated);
        }
    }
}
