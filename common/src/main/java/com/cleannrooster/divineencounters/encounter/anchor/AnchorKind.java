package com.cleannrooster.divineencounters.encounter.anchor;

/// What an authored position in an encounter arena is for.
///
/// Anchors are resolved once, when a structure is placed, from data markers embedded in its
/// template. Everything downstream queries the registry by kind instead of searching the world —
/// a boss should never scan cage blocks looking for somewhere to perch.
public enum AnchorKind {
    /// Elevated spot on a cage rib, branch or root that a boss can occupy and dive from.
    PERCH,
    /// A cage section that can participate in arena hazards, such as roots closing inward.
    HAZARD_SEGMENT,
    /// The middle of the fightable space. Also the parking spot for an unresolved entity.
    ARENA_CENTRE,
    /// Where the boss enters the fight.
    BOSS_SPAWN,
    /// Peripheral spots suitable for environmental cues and false silhouettes.
    ECHO_POINT,
    /// Ground-level positions around the arena edge, for repositioning without an attack.
    EDGE;

    /// Whether occupying this anchor puts an entity above the arena floor.
    public boolean isElevated() {
        return this == PERCH;
    }

    /// Parse a marker's metadata string. Markers are authored as `perch_1`, `hazard_north`,
    /// `arena_centre`, and so on — the leading token selects the kind, so a template author can
    /// add more of a kind without touching code.
    public static AnchorKind fromMarker(String metadata) {
        var lower = metadata.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("perch")) {
            return PERCH;
        }
        if (lower.startsWith("hazard") || lower.startsWith("root_segment")) {
            return HAZARD_SEGMENT;
        }
        if (lower.startsWith("arena_centre") || lower.startsWith("arena_center")) {
            return ARENA_CENTRE;
        }
        if (lower.startsWith("boss_spawn") || lower.startsWith("spawn")) {
            return BOSS_SPAWN;
        }
        if (lower.startsWith("echo")) {
            return ECHO_POINT;
        }
        return EDGE;
    }
}
