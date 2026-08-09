package com.cleannrooster.divineencounters.encounter.structure;

/// What happens to a manifested structure when its encounter ends.
public enum PersistenceMode {
    /// The structure stays. Whatever it replaced is gone for good.
    PERMANENT,

    /// The structure is temporary scenery belonging to the encounter, and the world goes back to
    /// how it was.
    ///
    /// This is the right default for anything that erupts around a player. An arena that
    /// materialises wherever someone happens to be standing has no business leaving permanent
    /// damage — especially now that arenas are triggered by omens rather than placed at authored
    /// sites, so the ground underneath is arbitrary and may well be somewhere the player cares
    /// about.
    RESTORE_ORIGINAL;

    public boolean restores() {
        return this == RESTORE_ORIGINAL;
    }
}
