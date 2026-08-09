package com.cleannrooster.divineencounters.encounter.perception;

/// How hard it is to *meaningfully* observe something during an encounter.
///
/// This is the dial that darkness turns. Darkness and spatial indeterminacy are deliberately kept
/// as separate systems: darkness never relocates anything, it only shrinks the window in which a
/// player counts as watching. Losing observation is what grants positional freedom, and that
/// happens through {@link ObservationTracker}, not through here.
///
/// A profile is consulted by {@link ObservationCheck}. Later fight phases hand it stricter numbers,
/// so the same camera behaviour that kept a boss pinned in phase one no longer does in phase three.
///
/// ### The angles
/// Observation is judged by the angle between the player's look vector and the direction to the
/// target. Two angles rather than one, because a single threshold makes the state flicker whenever
/// a player holds the boss right at the edge of vision:
///
/// - within {@link #focusAngle} the target is squarely in view — this is what *starts* observation;
/// - out past {@link #releaseAngle} it has genuinely left view — this is what *ends* it.
///
/// The gap between them is the hysteresis band. Inside it, whatever the previous answer was wins.
///
/// @param focusAngle       half-angle, degrees, within which observation begins
/// @param releaseAngle     half-angle, degrees, beyond which observation ends (must exceed focusAngle)
/// @param maxDistance      beyond this, a player is too far away to count as watching, in blocks
/// @param requireLineOfSight whether intervening geometry breaks observation outright
/// @param graceTicks       how long observation must be absent before it counts as lost
/// @param renderFalloff    client-side fade shaping: how sharply the body dims toward the edge of
///                         vision. Higher fades harder. Purely presentational.
public record GloomProfile(
        float focusAngle,
        float releaseAngle,
        double maxDistance,
        boolean requireLineOfSight,
        int graceTicks,
        float renderFalloff
) {
    /// Phase one — stalking. Generous: nearly anywhere on screen counts as watching, and the boss
    /// has to work to break contact. This is where the player learns the rules.
    public static final GloomProfile STALKING =
            new GloomProfile(70.0f, 88.0f, 40.0, true, 20, 1.0f);

    /// Phase two — deepening. Peripheral vision starts failing to hold it.
    public static final GloomProfile DEEPENING =
            new GloomProfile(52.0f, 70.0f, 30.0, true, 16, 1.8f);

    /// Phase three — deep malice. Only near-direct observation pins it, and it slips away fast.
    public static final GloomProfile DEEP =
            new GloomProfile(38.0f, 55.0f, 24.0, true, 12, 2.6f);

    /// The No Witness sequence. Deliberately the harshest, but still never zero — direct eye
    /// contact must always be able to constrain it, or the mechanic stops being counterplay.
    public static final GloomProfile NO_WITNESS =
            new GloomProfile(28.0f, 42.0f, 20.0, true, 10, 3.4f);

    public GloomProfile {
        if (releaseAngle <= focusAngle) {
            throw new IllegalArgumentException(
                    "releaseAngle must exceed focusAngle, or observation has no hysteresis band: "
                            + focusAngle + " -> " + releaseAngle);
        }
    }

    /// Grace period expressed in seconds, for readability at call sites and in tuning notes.
    public float graceSeconds() {
        return this.graceTicks / 20.0f;
    }
}
