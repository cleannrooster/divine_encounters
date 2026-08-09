package com.cleannrooster.divineencounters.encounter.structure;

/// The sequence a structure assembles in.
///
/// Different encounters want visibly different assemblies. A tower should climb out of the ground;
/// a cage should close inward around whoever is inside it. Making that a property of the plan
/// rather than of the engine means one manifestation implementation serves both, and a third
/// encounter can pick a fourth shape without touching any of it.
public enum ManifestationOrder {
    /// Climbs from the foundations up. The shape a tower or a monument wants.
    BOTTOM_TO_TOP,

    /// Descends from the highest blocks down — something lowering into place.
    TOP_TO_BOTTOM,

    /// Starts at the perimeter and closes toward the centre. The shape a trap wants: the walls
    /// arrive before the ceiling does, and the player watches the gap shrink.
    OUTSIDE_IN,

    /// Grows outward from the centre.
    INSIDE_OUT,

    /// Follows an authored sequence, taken from `stage_N_*` markers placed in the template itself.
    ///
    /// The other four are computed from geometry; this one is designed. It exists because an
    /// authored assembly ("roots, then ribs, then walls, then canopy") reads far better than
    /// anything a distance function produces, and because moving a marker in the template should be
    /// enough to re-choreograph it with no code change at all.
    STAGED;

    /// Whether this ordering needs `stage_` markers to be present in the template.
    public boolean requiresStageMarkers() {
        return this == STAGED;
    }
}
