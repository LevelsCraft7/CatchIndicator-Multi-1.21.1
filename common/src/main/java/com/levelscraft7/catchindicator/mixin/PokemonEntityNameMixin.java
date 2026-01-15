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
            Object pokedexSource = resolvePokedexSource();
            if (pokedexSource == null) return DiscoveryStatus.UNKNOWN;
            // Voie officielle Cobblemon : ClientPokedexManager.getCaughtForms / getEncounteredForms
            DiscoveryStatus clientStatus = catchindicator$tryResolveFromClientPokedexManager(pokemon);
            if (clientStatus != null) return clientStatus;

            ResourceLocation speciesRL = safeSpeciesResourceLocation(pokemon);
            String formId = safeFormId(pokemon);
            DiscoveryStatus viaRecords = tryResolveFromSpeciesRecords(pokedexSource, speciesRL);
            if (viaRecords != null) return viaRecords;
            Object speciesObj = invokeFirst(pokemon, "getSpecies", "species");

            // Essai multi clés: certains pokedex utilisent "cobblemon:<id>", d'autres "<id>"
            String showdown = safeSpeciesId(pokemon);

            ResourceLocation rlA = speciesRL;
            if (rlA == null && showdown != null && !showdown.isBlank()) {
                try {
                    rlA = ResourceLocation.parse(showdown.contains(":") ? showdown : "cobblemon:" + showdown);
                } catch (Throwable ignored) {
                }
            }

            ResourceLocation rlB = null;
            if (showdown != null && !showdown.isBlank()) {
                try {
                    rlB = ResourceLocation.parse(showdown.contains(":") ? showdown : "minecraft:" + showdown);
                } catch (Throwable ignored) {
                }
            }

            String formA = formId;
            String formB = (formId == null || formId.isBlank()) ? "normal" : formId;

            // 1) Robust path: try API style calls first (hasCaught, hasSeen) avec variantes
            Object caught = null;
            Object seen = null;

            // Variante 0: certaines versions attendent Species ou Pokemon, pas ResourceLocation
            if (speciesObj != null) {
                Object caught0 = invokeFirstWithArgs(pokedexSource, new Object[]{ speciesObj, formId },
                        "hasCaught", "has_caught", "hasCaught$default");
                if (!(caught0 instanceof Boolean)) {
                    caught0 = invokeFirstWithArgs(pokedexSource, new Object[]{ speciesObj },
                            "hasCaught", "has_caught", "hasCaught$default");
                }
                if (caught0 instanceof Boolean b && b) return DiscoveryStatus.CAUGHT;

                Object seen0 = invokeFirstWithArgs(pokedexSource, new Object[]{ speciesObj, formId },
                        "hasSeen", "has_seen", "hasSeen$default");
                if (!(seen0 instanceof Boolean)) {
                    seen0 = invokeFirstWithArgs(pokedexSource, new Object[]{ speciesObj },
                            "hasSeen", "has_seen", "hasSeen$default");
                }
                if (seen0 instanceof Boolean b && b) return DiscoveryStatus.SEEN;

            }

// Variante 0 bis: parfois l'API prend directement Pokemon
            Object caughtP = invokeFirstWithArgs(pokedexSource, new Object[]{ pokemon },
                    "hasCaught", "has_caught", "hasCaught$default");
            if (caughtP instanceof Boolean b && b) return DiscoveryStatus.CAUGHT;

            Object seenP = invokeFirstWithArgs(pokedexSource, new Object[]{ pokemon },
                    "hasSeen", "has_seen", "hasSeen$default");
            if (seenP instanceof Boolean b && b) return DiscoveryStatus.SEEN;

            ResourceLocation[] rls = new ResourceLocation[]{ rlA, rlB };
            String[] forms = new String[]{ formA, formB, null };

            for (ResourceLocation rl : rls) {
                if (rl == null) continue;

                for (String f : forms) {
                    if (caught == null) {
                        caught = invokeFirstWithArgs(pokedexSource, new Object[]{ rl, f },
                                "hasCaught", "has_caught", "hasCaught$default");
                    }
                    if (caught == null) {
                        caught = invokeFirstWithArgs(pokedexSource, new Object[]{ rl },
                                "hasCaught", "has_caught", "hasCaught$default");
                    }
                    if (caught instanceof Boolean b && b) return DiscoveryStatus.CAUGHT;

                    if (seen == null) {
                        seen = invokeFirstWithArgs(pokedexSource, new Object[]{ rl, f },
                                "hasSeen", "has_seen", "hasSeen$default");
                    }
                    if (seen == null) {
                        seen = invokeFirstWithArgs(pokedexSource, new Object[]{ rl },
                                "hasSeen", "has_seen", "hasSeen$default");
                    }
                    if (seen instanceof Boolean b && b) return DiscoveryStatus.SEEN;
                }
            }

            // 2) Fallback: map based storage (internal structure may vary)
            Map<?, ?> discovered = resolveDiscoveredMap(pokedexSource);
            if (discovered == null) return DiscoveryStatus.UNKNOWN;

            String showdownSpecies = safeSpeciesId(pokemon);
            String speciesKeyA = showdownSpecies;
            String speciesKeyB = (showdownSpecies != null && !showdownSpecies.contains(":"))
                    ? "cobblemon:" + showdownSpecies
                    : showdownSpecies;

            String formKey = (formId == null || formId.isBlank()) ? "normal" : formId;

            Object speciesForms =
                    mapGetByStringKey(discovered, speciesKeyA) != null ? mapGetByStringKey(discovered, speciesKeyA)
                            : mapGetByStringKey(discovered, speciesKeyB);

            if (speciesForms instanceof Map<?, ?> formsMap) {
                Object register = mapGetByStringKey(formsMap, formKey);
                if (register == null && (formId == null || formId.isBlank())) {
                    register = mapGetByStringKey(formsMap, "normal");
                }
                return parseRegister(register);
            }

            Object register =
                    mapGetByStringKey(discovered, speciesKeyA) != null ? mapGetByStringKey(discovered, speciesKeyA)
                            : mapGetByStringKey(discovered, speciesKeyB);

            if (register == null && formId != null && !formId.isBlank()) {
                register = mapGetByStringKey(discovered, speciesKeyA + ":" + formId);
                if (register == null && speciesKeyB != null) {
                    register = mapGetByStringKey(discovered, speciesKeyB + ":" + formId);
                }
            }

            return parseRegister(register);
        } catch (Throwable t) {
            LOGGER.debug("Failed to resolve discovery status", t);
            return DiscoveryStatus.UNKNOWN;
        }
    }

    private static Object resolvePokedexEntry(Pokemon pokemon) {
        Object species = invokeFirst(pokemon, "getSpecies", "species");
        if (species == null) return null;

        // Le plus probable
        Object entry = invokeFirst(species, "getPokedexEntry", "pokedexEntry");
        if (entry != null) return entry;

        // Fallback: scan champs du species pour trouver un PokedexEntry
        try {
            for (Field f : species.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(species);
                if (v == null) continue;

                String cn = v.getClass().getName();
                if (cn.endsWith("PokedexEntry") || cn.contains(".api.pokedex.entry.PokedexEntry")) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static DiscoveryStatus tryResolveFromSpeciesRecords(Object pokedexSource, ResourceLocation speciesRL) {
        if (pokedexSource == null || speciesRL == null) return null;

        try {
            Object recordsObj = invokeFirst(pokedexSource, "getSpeciesRecords", "speciesRecords", "getRecords", "records");
            if (!(recordsObj instanceof Map<?, ?> records)) return null;

            Object record = records.get(speciesRL);
            if (record == null) {
                // fallback string match au cas ou la key est un wrapper
                record = mapGetByStringKey(records, speciesRL.toString());
            }
            if (record == null) return null;

            // 1) Cas simple: méthode progress() ou getProgress() retourne un enum ou string
            Object progress = invokeFirst(record, "getProgress", "progress", "getEntryProgress", "entryProgress");
            if (progress != null) {
                String s = progress.toString();
                if (s.contains("CAUGHT")) return DiscoveryStatus.CAUGHT;
                if (s.contains("ENCOUNTERED")) return DiscoveryStatus.SEEN;
                if (s.contains("NONE")) return DiscoveryStatus.UNKNOWN;
            }

            // 2) Cas simple: booleans
            Object caught = invokeFirst(record, "isCaught", "getCaught", "caught");
            if (caught instanceof Boolean b && b) return DiscoveryStatus.CAUGHT;

            Object encountered = invokeFirst(record, "isEncountered", "getEncountered", "encountered", "isSeen", "getSeen", "seen");
            if (encountered instanceof Boolean b && b) return DiscoveryStatus.SEEN;

            // 3) Dernier recours: scan fields pour trouver un enum progress dedans
            try {
                for (Field f : record.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(record);
                    if (v == null) continue;

                    String s = v.toString();
                    if (s.contains("CAUGHT")) return DiscoveryStatus.CAUGHT;
                    if (s.contains("ENCOUNTERED")) return DiscoveryStatus.SEEN;
                }
            } catch (Throwable ignored) {
            }

            return DiscoveryStatus.UNKNOWN;
        } catch (Throwable ignored) {
            return null;
        }
    }


    private static Map<?, ?> resolveDiscoveredMap(Object pokedexSource) {
        Object map = invokeFirst(pokedexSource,
                "getDiscoveredList", "getDiscoveredEntries", "getDiscovered", "getDiscoveryMap",
                "getEntries", "entries", "discovered", "discoveredList");
        if (map instanceof Map<?, ?> m) return m;

        // maybe nested in another object
        Object inner = invokeFirst(pokedexSource,
                "getPokedex", "pokedex", "getPokedexManager", "getPokedexData", "getPokedexState");
        if (inner != null) {
            Object map2 = invokeFirst(inner,
                    "getDiscoveredList", "getDiscoveredEntries", "getDiscovered", "getDiscoveryMap",
                    "getEntries", "entries", "discovered", "discoveredList");
            if (map2 instanceof Map<?, ?> m2) return m2;
        }

        // last resort: scan fields for a Map
        try {
            for (Field f : pokedexSource.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object v = f.get(pokedexSource);
                    if (v instanceof Map<?, ?> m3) return m3;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
                    "name", "getName",
                    "showdownId", "getShowdownId",
                    "id", "getId");
            String s = (id != null) ? id.toString() : null;

            if (s == null || s.isBlank()) return null;
            if (s.equalsIgnoreCase("normal")) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
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

    private static Object resolvePokedexSource() {
        Object clientSingleton = resolveCobblemonClientSingleton();
        if (clientSingleton != null) {
            // Cobblemon 1.21.x: source fiable côté client
            Object clientPokedex = invokeFirst(clientSingleton,
                    "getClientPokedexData", "getClientPokedexManager", "clientPokedex", "clientPokedexData");
            if (clientPokedex != null) return clientPokedex;

            // Fallback anciens noms
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
    private static DiscoveryStatus catchindicator$tryResolveFromClientPokedexManager(Pokemon pokemon) {
        try {
            Object cobblemonClient = resolveCobblemonClientSingleton();
            if (cobblemonClient == null) return null;

            Object clientDex = invokeFirst(cobblemonClient, "getClientPokedexData");
            if (clientDex == null) return null;

            Object species = invokeFirst(pokemon, "getSpecies", "species");
            if (species == null) return null;

            Object entry = invokeFirst(species, "getPokedexEntry", "pokedexEntry");
            if (entry == null) return null;

            Object caughtFormsObj = invokeFirstWithArgs(clientDex, new Object[]{ entry }, "getCaughtForms");
            if (caughtFormsObj instanceof java.util.Collection<?> c && !c.isEmpty()) {
                return DiscoveryStatus.CAUGHT;
            }

            Object encounteredFormsObj = invokeFirstWithArgs(clientDex, new Object[]{ entry }, "getEncounteredForms");
            if (encounteredFormsObj instanceof java.util.Collection<?> e && !e.isEmpty()) {
                return DiscoveryStatus.SEEN;
            }

            return DiscoveryStatus.UNKNOWN;
        } catch (Throwable ignored) {
            return null;
        }
    }

}
