package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.levelscraft7.catchindicator.client.DiscoveryStatus;
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

/**
 * Client-side: modifies ONLY the in-world Pokémon entity name, not the Pokémon model name everywhere.
 *
 * CAUGHT  -> original name + icon
 * SEEN    -> original name
 * UNKNOWN -> hide name as "???"
 *
 * Cobblemon's Pokédex internals change across versions. This uses reflection with multiple fallbacks
 * to stay resilient across 1.6.x and 1.7.x line changes.
 */
@Mixin(targets = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity")
public abstract class PokemonEntityNameMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("catchindicator");

    // Known (historical) client singletons. We try several to survive refactors.
    private static final String[] COBBLEMON_CLIENT_SINGLETONS = new String[] {
            "com.cobblemon.mod.common.client.Client",
            "com.cobblemon.mod.common.client.CobblemonClient",
            "com.cobblemon.mod.common.client.CobblemonClientKt",
            "com.cobblemon.mod.common.Cobblemon"
    };

    // Icon glyph mapped via assets/catchindicator/font/default.json
    private static final Component CAUGHT_ICON = Component.literal("\ue100");

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void catchindicator$decorateWildName(CallbackInfoReturnable<Component> cir) {
        // Hard client guard: this mixin is in the "client" section, but keep it safe anyway.
        if (Minecraft.getInstance() == null || Minecraft.getInstance().player == null) return;

        Pokemon pokemon = getPokemonFromEntity(this);
        if (pokemon == null) return;

        if (!isWild(pokemon)) return;

        DiscoveryStatus status = getDiscoveryStatus(pokemon);
        Component original = cir.getReturnValue();

        if (status == DiscoveryStatus.CAUGHT) {
            MutableComponent out = original.copy();
            out.append(" ").append(CAUGHT_ICON);
            cir.setReturnValue(out);
        } else if (status == DiscoveryStatus.SEEN) {
            // keep as-is
        } else {
            cir.setReturnValue(Component.literal("???"));
        }
    }

    private static Pokemon getPokemonFromEntity(Object pokemonEntity) {
        // Prefer direct method name used by Cobblemon
        Object res = invokeFirst(pokemonEntity,
                "getPokemon", "pokemon", "getPokemon$common", "getPokemon$default");
        if (res instanceof Pokemon p) return p;

        // Fallback: look for a field of type Pokemon
        try {
            for (Field f : pokemonEntity.getClass().getDeclaredFields()) {
                if (Pokemon.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(pokemonEntity);
                    if (v instanceof Pokemon p2) return p2;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isWild(Pokemon pokemon) {
        // Cobblemon has moved ownership and trainer concepts around over time.
        // We'll treat "has ANY owner/trainer info" as not-wild.
        Object owner = invokeFirst(pokemon,
                "getOwnerUUID", "getOwnerUuid", "getOwnerId", "getOwner", "getOriginalTrainer");
        if (owner == null) return true;

        // Some versions return an enum-ish sentinel like NONE
        String s = owner.toString();
        return s == null || s.equalsIgnoreCase("NONE") || s.equalsIgnoreCase("null");
    }

    private static DiscoveryStatus getDiscoveryStatus(Pokemon pokemon) {
        try {
            Object pokedexSource = resolvePokedexSource();
            if (pokedexSource == null) return DiscoveryStatus.UNKNOWN;

            // 1) map-based storage
            Map<String, ?> discovered = resolveDiscoveredMap(pokedexSource);
            if (discovered == null) return DiscoveryStatus.UNKNOWN;

            String speciesId = safeSpeciesId(pokemon);
            String formId = safeFormId(pokemon);

            Object speciesForms = discovered.get(speciesId);
            if (speciesForms instanceof Map<?, ?> formsMap) {
                Object register = formsMap.get(formId);
                return parseRegister(register);
            }

            // Some implementations key by showdown form id directly
            Object register = discovered.get(speciesId + ":" + formId);
            if (register == null) register = discovered.get(formId);
            return parseRegister(register);
        } catch (Throwable t) {
            LOGGER.debug("Failed to resolve discovery status", t);
            return DiscoveryStatus.UNKNOWN;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> resolveDiscoveredMap(Object pokedexSource) {
        Object map = invokeFirst(pokedexSource,
                "getDiscoveredList", "getDiscoveredEntries", "getDiscovered", "getDiscoveryMap",
                "getEntries", "entries", "discovered", "discoveredList");
        if (map instanceof Map<?, ?> m) return (Map<String, ?>) m;

        // maybe nested in another object
        Object inner = invokeFirst(pokedexSource,
                "getPokedex", "pokedex", "getPokedexManager", "getPokedexData", "getPokedexState");
        if (inner != null) {
            Object map2 = invokeFirst(inner,
                    "getDiscoveredList", "getDiscoveredEntries", "getDiscovered", "getDiscoveryMap",
                    "getEntries", "entries", "discovered", "discoveredList");
            if (map2 instanceof Map<?, ?> m2) return (Map<String, ?>) m2;
        }

        // last resort: scan fields for a Map
        try {
            for (Field f : pokedexSource.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(pokedexSource);
                    if (v instanceof Map<?, ?> m3) return (Map<String, ?>) m3;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static DiscoveryStatus parseRegister(Object register) {
        if (register == null) return DiscoveryStatus.UNKNOWN;

        // field "status"
        Object statusField = readFieldIfExists(register, "status");
        if (statusField != null) return DiscoveryStatus.fromString(statusField.toString());

        // methods like getStatus()
        Object statusMethod = invokeFirst(register, "getStatus", "status");
        if (statusMethod != null) return DiscoveryStatus.fromString(statusMethod.toString());

        // boolean style
        Object caught = invokeFirst(register, "isCaught", "getCaught", "caught");
        if (caught instanceof Boolean b && b) return DiscoveryStatus.CAUGHT;

        Object seen = invokeFirst(register, "isSeen", "getSeen", "seen");
        if (seen instanceof Boolean b && b) return DiscoveryStatus.SEEN;

        return DiscoveryStatus.UNKNOWN;
    }

    private static String safeSpeciesId(Pokemon pokemon) {
        try {
            Object species = invokeFirst(pokemon, "getSpecies", "species");
            Object id = invokeFirst(species, "showdownId", "getShowdownId", "id", "getId");
            return id != null ? id.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeFormId(Pokemon pokemon) {
        try {
            Object form = invokeFirst(pokemon, "getForm", "form");
            Object id = invokeFirst(form,
                    "formOnlyShowdownId", "getFormOnlyShowdownId",
                    "showdownId", "getShowdownId",
                    "id", "getId");
            return id != null ? id.toString() : "normal";
        } catch (Throwable ignored) {
            return "normal";
        }
    }

    private static Object resolvePokedexSource() {
        Object clientSingleton = resolveCobblemonClientSingleton();
        if (clientSingleton != null) {
            Object pokedex = invokeFirst(clientSingleton,
                    "getPokedex", "pokedex", "getPokedexManager", "getPokedexData",
                    "getPokedexState", "getPlayerPokedex", "getDiscoveredList", "getDiscoveredEntries");
            return pokedex != null ? pokedex : clientSingleton;
        }

        // Fall back to player-attached data, if present
        Object player = Minecraft.getInstance().player;
        return invokeFirst(player,
                "getPokedex", "pokedex", "getCobblemonPokedex", "getPokedexData", "getPokedexManager");
    }

    private static Object resolveCobblemonClientSingleton() {
        for (String className : COBBLEMON_CLIENT_SINGLETONS) {
            try {
                Class<?> c = Class.forName(className);
                Object instance = readStaticFieldIfExists(c, "INSTANCE");
                if (instance != null) return instance;

                Object singleton = invokeStaticFirst(c, "getInstance", "instance", "getClient", "client");
                if (singleton != null) return singleton;
            } catch (ClassNotFoundException ignored) {
                // expected
            } catch (Throwable t) {
                LOGGER.debug("Error while resolving Cobblemon client singleton: {}", className, t);
            }
        }
        return null;
    }

    private static Object readStaticFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readFieldIfExists(Object target, String fieldName) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeStaticFirst(Class<?> clazz, String... names) {
        for (String n : names) {
            try {
                Method m = clazz.getDeclaredMethod(n);
                m.setAccessible(true);
                return m.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeFirst(Object target, String... names) {
        if (target == null) return null;
        for (String n : names) {
            try {
                Method m = target.getClass().getDeclaredMethod(n);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
