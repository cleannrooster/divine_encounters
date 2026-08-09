package com.cleannrooster.divineencounters.encounter;

/// Lifecycle of one arena encounter.
///
/// `DORMANT` changed meaning when arenas moved from worldgen sites to omens: it no longer describes
/// a place waiting in the world, it describes an encounter that has been created but whose arena has
/// not started rising yet — the warning beat between "conditions are met" and "the trap springs".
public enum EncounterState {
    /// Created, warning the player, arena not yet building. The last moment they can walk away.
    DORMANT(false),
    /// The arena is assembling around them.
    MANIFESTING(true),
    /// Fully built; the boss is being placed.
    SEALED(true),
    /// The fight.
    ACTIVE(true),
    /// The boss is down and the arena is coming apart.
    RETRACTING(true),
    /// Finished. Terrain restored, tickets released, nothing left to tick.
    COMPLETED(false);

    private final boolean needsChunks;

    EncounterState(boolean needsChunks) {
        this.needsChunks = needsChunks;
    }

    /// Whether the arena's chunks must stay loaded. Holding tickets through a fight is what stops a
    /// player walking twenty blocks away and leaving a half-built arena frozen mid-assembly.
    public boolean needsChunks() {
        return this.needsChunks;
    }

    public boolean isFinished() {
        return this == COMPLETED;
    }

    /// Whether the boss should exist right now.
    public boolean expectsBoss() {
        return this == ACTIVE;
    }
}
