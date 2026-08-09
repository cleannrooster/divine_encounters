package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/// Every arena the mod knows about, keyed by id.
///
/// Persistence stores an arena's id rather than its contents, so a saved encounter reloads against
/// the *current* definition. That means retuning an arena's pacing or swapping its template takes
/// effect on existing worlds instead of replaying a stale copy.
public final class ArenaRegistry {
    private static final Map<ResourceLocation, ArenaDefinition> ARENAS = new LinkedHashMap<>();

    private ArenaRegistry() {
    }

    public static ArenaDefinition register(ArenaDefinition definition) {
        var previous = ARENAS.putIfAbsent(definition.id(), definition);
        if (previous != null && previous != definition) {
            throw new IllegalStateException("Duplicate arena id: " + definition.id());
        }
        return definition;
    }

    public static @Nullable ArenaDefinition get(ResourceLocation id) {
        return ARENAS.get(id);
    }

    /// Look up an arena named by saved data. Logs rather than throwing, so a world saved with a mod
    /// version that had an extra arena still loads — that encounter is simply dropped.
    public static @Nullable ArenaDefinition lookupForLoad(ResourceLocation id) {
        var arena = ARENAS.get(id);
        if (arena == null) {
            DivineEncounters.LOGGER.warn("Saved encounter references unknown arena {}", id);
        }
        return arena;
    }

    public static Collection<ArenaDefinition> all() {
        return Collections.unmodifiableCollection(ARENAS.values());
    }
}
