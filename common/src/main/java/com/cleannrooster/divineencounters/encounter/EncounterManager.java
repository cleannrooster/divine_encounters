package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.encounter.structure.TemplateBlocks;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/// Server-side entry point for arena encounters: event wiring plus the one call that starts one.
///
/// Deliberately thin. The per-level state lives in {@link EncounterSavedData} so that persistence
/// and ticking share a single owner, and this class only decides *when* that gets driven.
public final class EncounterManager {
    /// Two arenas this close together would overlap footprints and fight over the same terrain.
    private static final double MIN_SEPARATION = 96.0;

    private EncounterManager() {
    }

    /// Called from common init on both loaders.
    public static void register() {
        TickEvent.SERVER_LEVEL_POST.register(level -> EncounterSavedData.get(level).tick(level));

        // Shutting down mid-arena would otherwise leave the structure standing and the terrain
        // under it lost, since retraction is what restores it.
        LifecycleEvent.SERVER_LEVEL_UNLOAD.register(level ->
                EncounterSavedData.get(level).abortAll(level));
    }

    /// Begin an encounter at a position, unless one is already nearby.
    ///
    /// Returns the encounter so a caller can report on it, or null when the location is refused.
    /// Site suitability is *not* checked here — that belongs to whatever is asking (an omen's
    /// eligibility rules, or a debug command that deliberately skips them).
    public static @Nullable ArenaEncounter start(ServerLevel level, ArenaDefinition definition,
                                                 BlockPos origin) {
        // Pre-flight the template. Without this a missing or misnamed template is only discovered
        // once the encounter is already ticking, by which point the caller has committed — and for
        // an omen-triggered arena that means the player has paid their omen for nothing.
        if (!TemplateBlocks.exists(level, definition.templateId())) {
            DivineEncounters.LOGGER.warn("Arena {} cannot start: template {} is missing",
                    definition.id(), definition.templateId());
            return null;
        }
        var data = EncounterSavedData.get(level);
        if (data.hasEncounterNear(origin, MIN_SEPARATION)) {
            return null;
        }
        var encounter = new ArenaEncounter(definition, origin);
        data.add(encounter);
        return encounter;
    }

    /// The live encounter containing a position, if any.
    public static @Nullable ArenaEncounter encounterAt(ServerLevel level, BlockPos pos) {
        for (var encounter : EncounterSavedData.get(level).encounters()) {
            if (!encounter.isFinished() && encounter.bounds().isInside(pos)) {
                return encounter;
            }
        }
        return null;
    }

    public static boolean hasEncounterNear(ServerLevel level, BlockPos pos, double radius) {
        return EncounterSavedData.get(level).hasEncounterNear(pos, radius);
    }
}
