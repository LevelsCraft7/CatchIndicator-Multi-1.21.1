package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;

@Mixin({Pokemon.class})
public abstract class PokemonMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("alreadycatch");

    @Inject(
            method = {"getDisplayName"},
            at = {@At("RETURN")},
            cancellable = true
    )
    private void modifyPokemonName(CallbackInfoReturnable<class_2561> cir) {
        if (class_310.method_1551().field_1724 != null) {
            Pokemon pokemon = (Pokemon)this;
            if (this.isPokemonWild(pokemon)) {
                String status = this.getPokemonStatus(pokemon);
                class_2561 originalName = (class_2561)cir.getReturnValue();
                class_5250 modifiedName = class_2561.method_43470(originalName.getString());
                if ("CAUGHT".equalsIgnoreCase(status)) {
                    class_5250 pokeballIcon = class_2561.method_43470("\ue100");
                    modifiedName.method_27693(" ").method_10852(pokeballIcon);
                } else if ("SEEN".equalsIgnoreCase(status)) {
                    class_2561.method_43470(originalName.getString());
                } else {
                    modifiedName = class_2561.method_43470("???");
                }

                cir.setReturnValue(modifiedName);
            }
        }
    }

    private String getPokemonStatus(Pokemon pokemon) {
        if (class_310.method_1551().field_1724 == null) {
            return "UNKNOWN";
        } else {
            Map<String, Map<String, DiscoveryRegister>> discoveredList = Client.INSTANCE.getDiscoveredList();
            String speciesId = pokemon.getSpecies().showdownId();
            String formId = pokemon.getForm().formOnlyShowdownId();
            Map<String, DiscoveryRegister> speciesForms = (Map)discoveredList.get(speciesId);
            if (speciesForms == null) {
                return "UNKNOWN";
            } else {
                DiscoveryRegister register = (DiscoveryRegister)speciesForms.get(formId);
                if (register == null) {
                    return "UNKNOWN";
                } else if (this.hasField(register, "status")) {
                    Object statusObj = this.getFieldValue(register, "status");
                    return statusObj != null ? statusObj.toString() : "UNKNOWN";
                } else {
                    return "UNKNOWN";
                }
            }
        }
    }

    private boolean isPokemonWild(Pokemon pokemon) {
        Object trainer = pokemon.getOriginalTrainer();
        return trainer == null || "NONE".equalsIgnoreCase(trainer.toString());
    }

    private boolean hasField(Object obj, String fieldName) {
        try {
            obj.getClass().getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException var4) {
            return false;
        }
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (IllegalAccessException | NoSuchFieldException var4) {
            return null;
        }
    }
}