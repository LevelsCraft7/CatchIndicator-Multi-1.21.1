package com.levelscraft7.catchindicator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PokedexRefreshManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("catchindicator");
    private static final String POKEMON_ENTITY_CLASS = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity";
    private static final Set<String> CAUGHT_SPECIES = new HashSet<>();
    private static final AtomicBoolean NEEDS_REFRESH = new AtomicBoolean(false);

    private PokedexRefreshManager() {
    }

    public static boolean isSpeciesCaught(String anyId) {
        if (anyId == null || anyId.isBlank()) return false;
        for (String normalized : normalizeIds(anyId)) {
            if (CAUGHT_SPECIES.contains(normalized)) return true;
        }
        return false;
    }

    public static boolean markSpeciesCaught(String anyId) {
        if (anyId == null) return false;
        boolean changed = false;
        for (String normalized : normalizeIds(anyId)) {
            if (CAUGHT_SPECIES.add(normalized)) {
                changed = true;
            }
        }
        return changed;
    }

    public static void onPokedexSync(Object clientPokedexManager) {
        LOGGER.debug("pokedex sync received");
        if (clientPokedexManager == null) return;

        Map<?, ?> records = resolveSpeciesRecords(clientPokedexManager);
        if (records == null || records.isEmpty()) return;

        boolean changed = false;

        for (Map.Entry<?, ?> entry : records.entrySet()) {
            Object key = entry.getKey();
            Object record = entry.getValue();
            if (key == null || record == null) continue;

            Object caughtForms = invokeFirstWithArgs(clientPokedexManager, new Object[]{ key }, "getCaughtForms");
            if (!(caughtForms instanceof java.util.Collection<?> c) || c.isEmpty()) {
                caughtForms = invokeFirstWithArgs(clientPokedexManager, new Object[]{ record }, "getCaughtForms");
            }

            if (caughtForms instanceof java.util.Collection<?> c2 && !c2.isEmpty()) {
                if (markSpeciesCaught(key.toString())) {
                    LOGGER.debug("caught status changed for {}", key);
                    changed = true;
                }

                Object speciesId = invokeFirst(record,
                        "getSpeciesId", "speciesId", "getId", "id", "getShowdownId", "showdownId");
                if (speciesId != null) {
                    markSpeciesCaught(speciesId.toString());
                }
            }
        }

        if (changed) {
            scheduleRefresh();
        }
    }

    private static void scheduleRefresh() {
        if (!NEEDS_REFRESH.compareAndSet(false, true)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            NEEDS_REFRESH.set(false);
            return;
        }
        mc.execute(PokedexRefreshManager::refreshWorldNametags);
    }

    private static void refreshWorldNametags() {
        if (!NEEDS_REFRESH.compareAndSet(true, false)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;

        int count = 0;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == null) continue;
            if (!entity.getClass().getName().equals(POKEMON_ENTITY_CLASS)) continue;

            boolean visible = entity.isCustomNameVisible();
            entity.setCustomNameVisible(!visible);
            entity.setCustomNameVisible(visible);
            count++;
        }
        LOGGER.debug("refresh executed: {} entities", count);
    }

    private static Set<String> normalizeIds(String raw) {
        Set<String> ids = new HashSet<>();
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return ids;

        ids.add(trimmed);

        String namespaced = trimmed.contains(":") ? trimmed : "cobblemon:" + trimmed;
        ids.add(namespaced);

        try {
            ResourceLocation rl = ResourceLocation.parse(namespaced);
            ids.add(rl.toString());
            ids.add(rl.getPath());
        } catch (Throwable ignored) {
        }

        return ids;
    }

    private static Map<?, ?> resolveSpeciesRecords(Object clientPokedexManager) {
        Object recordsObj = invokeFirst(clientPokedexManager,
                "getSpeciesRecords", "speciesRecords", "getRecords", "records");
        if (recordsObj == null) {
            recordsObj = readFieldIfExists(clientPokedexManager, "speciesRecords", "records");
        }
        if (recordsObj instanceof Map<?, ?> records) {
            return records;
        }
        return null;
    }

    private static Object readFieldIfExists(Object target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field f = target.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value != null) return value;
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
}
