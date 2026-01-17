package com.levelscraft7.catchindicator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
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
        LOGGER.warn("pokedex sync received");

        if (clientPokedexManager == null) return;

        Map<?, ?> records = resolveSpeciesRecords(clientPokedexManager);
        if (records == null || records.isEmpty()) return;

        boolean changed = false;

        for (Map.Entry<?, ?> entry : records.entrySet()) {
            if (updateCaughtFromRecord(entry.getKey(), entry.getValue())) {
                changed = true;
            }
        }

        if (changed) {
            scheduleRefresh();
        }


    }


    public static void onRecordUpdate(Object clientPokedexManager, Object key, Object record) {
        if (clientPokedexManager == null) return;
        if (updateCaughtFromRecord(key, record)) {
            scheduleRefresh();
        }
    }

    private static void scheduleRefresh() {
        if (!NEEDS_REFRESH.compareAndSet(false, true)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        // décale le refresh d'un tick client
        mc.execute(() -> {
            // encore un tick plus tard pour être sûr
            mc.execute(PokedexRefreshManager::refreshWorldNametags);
        });
    }

    private static void refreshWorldNametags() {
        LOGGER.debug("FORCED REFRESH THIS TICK");
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

    /**
     * Cobblemon 1.7 moved client Pokédex to instanced player data.
     * We treat a species as CAUGHT if either the species record or any form record has knowledge == CAUGHT.
     */
    private static boolean updateCaughtFromRecord(Object key, Object record) {
        if (key == null && record == null) return false;
        if (record == null) return false;

        if (!isRecordCaught(record)) return false;

        boolean changed = false;
        if (key != null && markSpeciesCaught(key.toString())) {
            LOGGER.debug("caught status changed for {}", key);
            changed = true;
        }

        Object speciesId = invokeFirst(record, "getId", "id", "getSpeciesId", "speciesId");
        if (speciesId != null && markSpeciesCaught(speciesId.toString())) {
            LOGGER.debug("caught status changed for {}", speciesId);
            changed = true;
        }

        return changed;
    }

    private static boolean isRecordCaught(Object speciesDexRecord) {
        if (speciesDexRecord == null) return false;

        // Fast path: SpeciesDexRecord.getKnowledge() == CAUGHT
        Object knowledge = invokeFirst(speciesDexRecord, "getKnowledge", "getEntryProgress", "getProgress", "knowledge");
        if (isProgressCaught(knowledge)) return true;

        // Fallback: hasAtLeast(CAUGHT)
        Object caughtEnum = resolvePokedexProgressCaught();
        if (caughtEnum != null) {
            Object hasAtLeast = invokeFirstWithArgs(speciesDexRecord, new Object[]{ caughtEnum }, "hasAtLeast");
            if (hasAtLeast instanceof Boolean b && b) return true;
        }

        // Deep fallback: scan formRecords map values and check each FormDexRecord.getKnowledge()
        Object formRecordsObj = readFieldIfExists(speciesDexRecord, "formRecords");
        if (formRecordsObj instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Object v : map.values()) {
                if (v == null) continue;
                Object fk = invokeFirst(v, "getKnowledge", "knowledge");
                if (isProgressCaught(fk)) return true;
            }
        }
        return false;
    }

    private static boolean isProgressCaught(Object progress) {
        if (progress == null) return false;
        String s = progress.toString();
        return s != null && s.equalsIgnoreCase("CAUGHT");
    }

    private static Object resolvePokedexProgressCaught() {
        try {
            Class<?> c = Class.forName("com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress");
            Field f = c.getDeclaredField("CAUGHT");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
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
