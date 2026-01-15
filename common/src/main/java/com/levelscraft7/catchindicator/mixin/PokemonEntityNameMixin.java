package com.levelscraft7.catchindicator.mixin;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.levelscraft7.catchindicator.client.DiscoveryStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Unique;


import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
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
@Mixin(com.cobblemon.mod.common.entity.pokemon.PokemonEntity.class)

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
    @Unique
    private static final Component CAUGHT_ICON = Component.literal("● CATCH ●");

    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void catchindicator$decorateWildName(CallbackInfoReturnable<Component> cir) {
        // Hard client guard: this mixin is in the "client" section, but keep it safe anyway.
        if (Minecraft.getInstance() == null || Minecraft.getInstance().player == null) return;
        LOGGER.debug("CatchIndicator getName mixin fired");

        Pokemon pokemon = getPokemonFromEntity(this);
        if (pokemon == null) return;

        boolean wild = isWild(pokemon);

// Fallback 1: owner peut être porté par l'entity en 1.21.x
        if (wild) {
            Object entityOwner = invokeFirst(this,
                    "getOwnerUUID", "getOwnerUuid", "getOwnerId", "getOwner",
                    "getOriginalTrainerUUID", "getOriginalTrainer");

            if (entityOwner != null) {
                String s = entityOwner.toString();
                if (s != null && !s.isBlank() && !s.equalsIgnoreCase("NONE") && !s.equalsIgnoreCase("null")) {
                    // Si ça ressemble à un UUID, on considère "owned"
                    try {
                        java.util.UUID.fromString(s);
                        wild = false;
                    } catch (Throwable ignored) {
                        // Même si ce n'est pas un UUID, une valeur non vide est déjà un bon signal "owned"
                        wild = false;
                    }
                }
            }
        }

// Fallback 2: scan des fields de l'entity pour repérer un UUID ou un Optional-like
        if (wild) {
            try {
                for (java.lang.reflect.Field f : this.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(this);

                    if (v instanceof java.util.UUID) {
                        wild = false;
                        break;
                    }

                    Object optVal = invokeFirst(v, "orElse", "get");
                    if (optVal instanceof java.util.UUID) {
                        wild = false;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        DiscoveryStatus status = getDiscoveryStatus(pokemon);
        Component original = cir.getReturnValue();

        // Si ce n'est pas sauvage, on le traite comme capturé
        if (!wild) status = DiscoveryStatus.CAUGHT;

        // Garde fou uniquement pour les sauvages
        if (wild && status == DiscoveryStatus.UNKNOWN) return;

        if (status == DiscoveryStatus.CAUGHT) {
            MutableComponent out = original.copy();
            out.append(Component.literal(" ")).append(CAUGHT_ICON);
            cir.setReturnValue(out);

        } else if (status == DiscoveryStatus.SEEN) {
            // keep as-is
        } else {
            // Ici status ne peut plus être UNKNOWN pour les sauvages, donc ??? = vraiment "jamais vu"
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
        // 1) Si Cobblemon expose déjà un booléen, on le prend
        Object wildFlag = invokeFirst(pokemon, "isWild", "getIsWild");
        if (wildFlag instanceof Boolean b) return b;

        Object ownedFlag = invokeFirst(pokemon, "isOwned", "isPlayerOwned", "getIsOwned", "getOwned");
        if (ownedFlag instanceof Boolean b) return !b;

        // 2) Fallback owner uuid
        Object owner = invokeFirst(pokemon,
                "getOwnerUUID", "getOwnerUuid", "getOwnerId", "getOwner", "getOriginalTrainer", "getOriginalTrainerUUID");

        if (owner == null) return true;

        // Optional like
        try {
            Object optVal = invokeFirst(owner, "orElse", "get");
            if (optVal != null) owner = optVal;
        } catch (Throwable ignored) {
        }

        // UUID means owned
        if (owner instanceof java.util.UUID) return false;

        // Some versions store as string uuid
        String s = owner.toString();
        if (s == null) return true;

        if (s.equalsIgnoreCase("NONE") || s.equalsIgnoreCase("null")) return true;

        // If it looks like a UUID, assume owned
        try {
            java.util.UUID.fromString(s);
            return false;
        } catch (Throwable ignored) {
        }

        // If it looks like a UUID, assume owned
        try {
            java.util.UUID.fromString(s);
            return false;
        } catch (Throwable ignored) {
        }

// Fallback hard: scan fields for UUID or Optional<UUID>
        try {
            for (Field f : pokemon.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(pokemon);
                if (v instanceof java.util.UUID) return false;

                // Optional-like wrappers
                Object optVal = invokeFirst(v, "orElse", "get");
                if (optVal instanceof java.util.UUID) return false;
            }
        } catch (Throwable ignored) {
        }
                // Default conservative
        return true;
    }


    private static DiscoveryStatus getDiscoveryStatus(Pokemon pokemon) {
        try {
            Object clientPokedexManager = resolveClientPokedexManager();
            if (clientPokedexManager == null) return DiscoveryStatus.UNKNOWN;

            Object species = invokeFirst(pokemon, "getSpecies", "species");
            Object entry = null;
            if (species != null) {
                entry = invokeFirst(species, "getPokedexEntry", "pokedexEntry");
            }

            ResourceLocation speciesId = safeSpeciesResourceLocation(pokemon);

            Object record = resolveSpeciesRecord(clientPokedexManager, speciesId, species, entry);
            if (record == null) return DiscoveryStatus.UNKNOWN;

            Object progress = invokeFirst(record,
                    "getEntryProgress", "getProgress",
                    "entryProgress", "progress");
            if (progress == null) {
                progress = readFieldIfExists(record, "entryProgress", "progress");
            }
            return mapEntryProgress(progress);
        } catch (Throwable t) {
            LOGGER.debug("Failed to resolve discovery status", t);
            return DiscoveryStatus.UNKNOWN;
        }
    }

    private static Object mapGetByStringKey(Map<?, ?> map, String key) {
        if (map == null || key == null) return null;

        // Fast path: direct lookup
        Object direct = map.get(key);
        if (direct != null) return direct;

        // Slow path: keys might be ResourceLocation/Identifier/etc. Compare by toString().
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey();
            if (k != null && key.equalsIgnoreCase(k.toString())) {
                return e.getValue();
            }
        }
        return null;
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

    private static ResourceLocation safeSpeciesResourceLocation(Pokemon pokemon) {
        try {
            Object species = invokeFirst(pokemon, "getSpecies", "species");
            Object rl = invokeFirst(species,
                    "resourceIdentifier", "getResourceIdentifier",
                    "resourceLocation", "getResourceLocation",
                    "id", "getId");
            if (rl instanceof ResourceLocation r) return r;
            if (rl != null) return ResourceLocation.parse(rl.toString());
        } catch (Throwable ignored) {
        }

        String showdown = safeSpeciesId(pokemon);
        if (showdown == null || showdown.isBlank()) return null;
        if (!showdown.contains(":")) showdown = "cobblemon:" + showdown;
        try {
            return ResourceLocation.parse(showdown);
        } catch (Throwable ignored) {
            return null;
        }
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

    private static Object readFieldIfExists(Object target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Object value = readFieldIfExists(target, fieldName);
            if (value != null) return value;
        }
        return null;
    }

    private static Object invokeStaticFirst(Class<?> clazz, String... names) {
        for (String n : names) {
            try {
                Method m;
                try {
                    m = clazz.getDeclaredMethod(n);
                } catch (NoSuchMethodException e) {
                    m = clazz.getMethod(n);
                }
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
                Method m;
                try {
                    m = target.getClass().getDeclaredMethod(n);
                } catch (NoSuchMethodException e) {
                    m = target.getClass().getMethod(n);
                }
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeFirstWithArgs(Object target, Object[] args, String... names) {
        if (target == null) return null;

        for (String n : names) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (m.getName().equals(n) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
                for (Method m : target.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(n) && m.getParameterCount() == args.length) {
                        m.setAccessible(true);
                        return m.invoke(target, args);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Unique
    private static Object resolveClientPokedexManager() {
        Object cobblemonClient = resolveCobblemonClientSingleton();
        if (cobblemonClient != null) {
            Object manager = invokeFirst(cobblemonClient,
                    "getClientPokedexManager", "getClientPokedexData");
            if (manager != null) return manager;

            manager = readFieldIfExists(cobblemonClient, "clientPokedexManager", "clientPokedexData");
            if (manager != null) return manager;
        }

        try {
            Class<?> clazz = Class.forName("com.cobblemon.mod.common.client.ClientPokedexManager");
            Object instance = readStaticFieldIfExists(clazz, "INSTANCE");
            if (instance != null) return instance;
            return invokeStaticFirst(clazz, "getInstance", "instance");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Unique
    private static Object resolveSpeciesRecord(Object clientPokedexManager, ResourceLocation speciesId, Object species, Object entry) {
        if (clientPokedexManager == null) return null;

        Object direct = resolveSpeciesRecordDirect(clientPokedexManager, speciesId, species, entry);
        if (direct != null) return direct;

        Object recordsObj = invokeFirst(clientPokedexManager,
                "getSpeciesRecords", "speciesRecords", "getRecords", "records");
        if (recordsObj instanceof Map<?, ?> records) {
            Object record = null;
            if (speciesId != null) {
                record = records.get(speciesId);
                if (record != null) return record;
                record = mapGetByStringKey(records, speciesId.toString());
                if (record != null) return record;
            }

            if (species != null) {
                record = records.get(species);
                if (record != null) return record;
            }

            if (entry != null) {
                record = records.get(entry);
                if (record != null) return record;
            }

            if (speciesId != null) {
                return mapGetByStringKey(records, speciesId.toString());
            }
        }
        return null;
    }

    @Unique
    private static Object resolveSpeciesRecordDirect(Object clientPokedexManager, ResourceLocation speciesId, Object species, Object entry) {
        if (entry != null) {
            Object record = invokeFirstWithArgs(clientPokedexManager, new Object[]{ entry },
                    "getSpeciesRecord", "getRecord", "getSpeciesDexRecord");
            if (record != null) return record;
        }

        if (species != null) {
            Object record = invokeFirstWithArgs(clientPokedexManager, new Object[]{ species },
                    "getSpeciesRecord", "getRecord", "getSpeciesDexRecord");
            if (record != null) return record;
        }

        if (speciesId != null) {
            Object record = invokeFirstWithArgs(clientPokedexManager, new Object[]{ speciesId },
                    "getSpeciesRecord", "getRecord", "getSpeciesDexRecord");
            if (record != null) return record;
        }

        return null;
    }

    @Unique
    private static DiscoveryStatus mapEntryProgress(Object progress) {
        if (progress == null) return DiscoveryStatus.UNKNOWN;

        String normalized = progress.toString().toUpperCase(Locale.ROOT);
        if (normalized.contains("CAUGHT")) return DiscoveryStatus.CAUGHT;
        if (normalized.contains("SEEN") || normalized.contains("ENCOUNTERED")) return DiscoveryStatus.SEEN;
        return DiscoveryStatus.UNKNOWN;
    }

}
