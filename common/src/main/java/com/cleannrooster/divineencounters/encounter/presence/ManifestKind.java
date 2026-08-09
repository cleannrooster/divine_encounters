package com.cleannrooster.divineencounters.encounter.presence;

/// What a manifestation is *for*.
///
/// An attack does not pick coordinates. It asks for a category — "put me somewhere I can ambush
/// from behind" — and {@link CandidateResolver} finds and scores a position that satisfies it.
/// That indirection is what keeps the mechanic out of individual attack code, and it means new
/// attacks get valid positioning by naming a kind rather than reimplementing the search.
public enum ManifestKind {
    /// Behind or nearly behind the target — the classic ambush angle. Must be out of view.
    REAR_AMBUSH(120.0f, 180.0f, 2.5, 6.0, false, false),

    /// To one side, where a turning player will find it. Out of view.
    FLANK(65.0f, 130.0f, 3.0, 7.0, false, false),

    /// Elevated, on a cage rib or branch. Needs an anchor to exist; falls through gracefully when
    /// the encounter has no arena.
    PERCH(0.0f, 180.0f, 4.0, 12.0, true, false),

    /// Elevated and close enough to dive onto the target.
    POUNCE(0.0f, 180.0f, 3.5, 9.0, true, false),

    /// Deliberately *in* view, in front. The exception that proves the rule — some attacks want to
    /// be seen arriving, and this is the only kind that permits it.
    FRONTAL_REVEAL(0.0f, 55.0f, 4.0, 9.0, false, true),

    /// Far out at the edge of the space, for repositioning without committing to an attack.
    ARENA_EDGE(0.0f, 180.0f, 8.0, 16.0, false, false),

    /// Somewhere the player is plausibly about to look — used when a search should occasionally be
    /// rewarded rather than always coming up empty.
    REACQUIRE(30.0f, 110.0f, 4.0, 10.0, false, false);

    private final float minViewAngle;
    private final float maxViewAngle;
    private final double minDistance;
    private final double maxDistance;
    private final boolean requiresElevation;
    private final boolean allowsVisible;

    ManifestKind(float minViewAngle, float maxViewAngle, double minDistance, double maxDistance,
                 boolean requiresElevation, boolean allowsVisible) {
        this.minViewAngle = minViewAngle;
        this.maxViewAngle = maxViewAngle;
        this.minDistance = minDistance;
        this.maxDistance = maxDistance;
        this.requiresElevation = requiresElevation;
        this.allowsVisible = allowsVisible;
    }

    /// Minimum angle from the target's look direction, degrees. A `REAR_AMBUSH` at 120 means the
    /// candidate has to be well behind them.
    public float minViewAngle() {
        return this.minViewAngle;
    }

    public float maxViewAngle() {
        return this.maxViewAngle;
    }

    public double minDistance() {
        return this.minDistance;
    }

    public double maxDistance() {
        return this.maxDistance;
    }

    /// Whether only elevated candidates (perches) qualify.
    public boolean requiresElevation() {
        return this.requiresElevation;
    }

    /// Whether a candidate currently inside somebody's view is acceptable. False for everything
    /// except {@link #FRONTAL_REVEAL} — this is the flag that stops the player ever watching the
    /// boss blink into existence.
    public boolean allowsVisible() {
        return this.allowsVisible;
    }

    /// Whether this kind can be satisfied without any arena anchors registered.
    public boolean worksWithoutArena() {
        return !this.requiresElevation;
    }
}
