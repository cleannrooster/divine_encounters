package com.cleannrooster.divineencounters.encounter.presence;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/// Proposes raw positions an entity could manifest at. Sources suggest; they do not judge —
/// {@link CandidateResolver} performs all validation and scoring, so every source gets the same
/// rules applied to it.
///
/// Two ship with the mod: {@link RingCandidateSource}, which works anywhere and carries a fight
/// with no arena, and {@link AnchorCandidateSource}, which contributes authored perches and edges
/// when an arena structure has been placed. They compose — using both simply widens the pool.
@FunctionalInterface
public interface CandidateSource {
    /// Append proposed positions to `out`. Implementations should not filter for validity or
    /// visibility; proposing a position that turns out to be inside a wall is expected and cheap.
    void collect(CandidateContext context, List<Vec3> out);
}
