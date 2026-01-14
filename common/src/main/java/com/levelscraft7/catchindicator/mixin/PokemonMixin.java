package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

@Mixin({Pokemon.class})
public abstract class PokemonMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("alreadycatch");
    private static final String[] COBBLEMON_CLIENT_CLASSES = new String[] {
            "com.cobblemon.mod.common.client.Client",
            "com.cobblemon.mod.common.client.CobblemonClient"
    };

    @Inject(
            method = {"getDisplayName"},
            at = {@At("RETURN")},
            cancellable = true
    )
    private void modifyPokemonName(CallbackInfoReturnable<Component> cir) {
        if (Minecraft.getInstance().player != null) {
            Pokemon pokemon = (Pokemon)this;
            if (this.isPokemonWild(pokemon)) {
                String status = this.getPokemonStatus(pokemon);
                Component originalName = cir.getReturnValue();
                MutableComponent modifiedName = Component.literal(originalName.getString());
                if ("CAUGHT".equalsIgnoreCase(status)) {
                    MutableComponent pokeballIcon = Component.literal("\ue100");
                    modifiedName.append(" ").append(pokeballIcon);
                } else if ("SEEN".equalsIgnoreCase(status)) {
                    modifiedName = Component.literal(originalName.getString());
                } else {
                    modifiedName = Component.literal("???");
                }

                cir.setReturnValue(modifiedName);
            }
        }
    }

    private String getPokemonStatus(Pokemon pokemon) {
        if (Minecraft.getInstance().player == null) {
            return "UNKNOWN";
        }
        Map<String, Map<String, Object>> discoveredList = this.getDiscoveredList();
        if (discoveredList == null) {
            return "UNKNOWN";
        }
        String speciesId = pokemon.getSpecies().showdownId();
        String formId = pokemon.getForm().formOnlyShowdownId();
        Map<String, Object> speciesForms = discoveredList.get(speciesId);
        if (speciesForms == null) {
            return "UNKNOWN";
        }
        Object register = speciesForms.get(formId);
        if (register == null) {
            return "UNKNOWN";
        }
        if (this.hasField(register, "status")) {
            Object statusObj = this.getFieldValue(register, "status");
            return statusObj != null ? statusObj.toString() : "UNKNOWN";
        }
        return "UNKNOWN";
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

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getDiscoveredList() {
        Object clientInstance = this.getCobblemonClientInstance();
        if (clientInstance == null) {
            return null;
        }
        Object discoveredList = this.invokeMethod(clientInstance, "getDiscoveredList");
        if (discoveredList instanceof Map<?, ?> list) {
            return (Map<String, Map<String, Object>>) list;
        }
        return null;
    }

    private Object getCobblemonClientInstance() {
        for (String className : COBBLEMON_CLIENT_CLASSES) {
            try {
                Class<?> clientClass = Class.forName(className);
                Object instance = this.getStaticFieldValue(clientClass, "INSTANCE");
                if (instance != null) {
                    return instance;
                }
                Object singleton = this.invokeStaticMethod(clientClass, "getInstance");
                if (singleton != null) {
                    return singleton;
                }
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Cobblemon client class not found: {}", className);
            }
        }
        return null;
    }

    private Object getStaticFieldValue(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (IllegalAccessException | NoSuchFieldException ignored) {
            return null;
        }
    }

    private Object invokeStaticMethod(Class<?> clazz, String methodName) {
        try {
            Method method = clazz.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private Object invokeMethod(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
