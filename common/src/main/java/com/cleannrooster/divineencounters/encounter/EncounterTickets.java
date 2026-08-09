package com.cleannrooster.divineencounters.encounter;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Comparator;

/// Keeps an arena's chunks loaded for as long as its encounter needs them.
///
/// Without this, a player who steps outside the arena's chunk radius mid-fight would freeze the
/// encounter: manifestation batches stop ticking, the boss stops thinking, and the arena is left
/// half-built. Tickets are added when an encounter starts and released the moment it completes —
/// never held longer, since a leaked ticket keeps chunks loaded for the rest of the session.
public final class EncounterTickets {
    /// Radius in chunks around the arena centre. Comfortably covers a 48-block footprint plus the
    /// margin an entity needs to path and be tracked at the edges.
    private static final int RADIUS = 3;

    private static final TicketType<ChunkPos> ENCOUNTER = TicketType.create(
            "divine_encounters_arena", Comparator.comparingLong(ChunkPos::toLong));

    private EncounterTickets() {
    }

    public static void acquire(ServerLevel level, BoundingBox bounds) {
        var centre = centreOf(bounds);
        level.getChunkSource().addRegionTicket(ENCOUNTER, centre, RADIUS, centre);
    }

    public static void release(ServerLevel level, BoundingBox bounds) {
        var centre = centreOf(bounds);
        level.getChunkSource().removeRegionTicket(ENCOUNTER, centre, RADIUS, centre);
    }

    private static ChunkPos centreOf(BoundingBox bounds) {
        return new ChunkPos((bounds.minX() + bounds.maxX()) >> 1 >> 4,
                (bounds.minZ() + bounds.maxZ()) >> 1 >> 4);
    }
}
