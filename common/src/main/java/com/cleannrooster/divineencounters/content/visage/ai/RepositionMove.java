package com.cleannrooster.divineencounters.content.visage.ai;

/// A repositioning action: a short, fast, non-damaging move that changes the angle of the duel before an
/// attack lands.
///
/// These are first-class actions, selected the same way attacks are, rather than movement smuggled into
/// an attack's implementation. That separation is deliberate — Valkyrie's Passage is an attack that
/// happens to travel, a Rear Pass is travel that happens to end somewhere useful, and keeping them
/// distinct is what stops "she moved" and "she hit me" from becoming the same event.
///
/// Every move is an arc around the target rather than a straight line or a blink: she sweeps through
/// space on a visible curve, banking into the turn, so the player can read where she is going.
///
/// @param sweepDegrees  how far around the target she travels; sign is chosen at runtime per side
/// @param durationTicks how long the arc takes — the main dial on readability
/// @param radiusStart   orbit radius she begins at, blocks
/// @param radiusEnd     orbit radius she finishes at; ending tighter than she started keeps pressure on
/// @param riseHeight    height gained across the arc, blocks; positive sweeps her up and over
/// @param bankDegrees   how hard she rolls into the turn, degrees
/// @param pitchDegrees  body pitch held through the arc, degrees; negative noses up
/// @param followUp      whether this move exists specifically to set up an immediate attack
public record RepositionMove(
        String id,
        float sweepDegrees,
        int durationTicks,
        double radiusStart,
        double radiusEnd,
        double riseHeight,
        float bankDegrees,
        float pitchDegrees,
        FollowUp followUp
) {
    /// What the reposition is for, which biases what she does when it ends.
    public enum FollowUp {
        /// Prefer a quick basic immediately.
        FAST_STRIKE,
        /// Prefer a heavy attack — she has bought herself the angle for it.
        HEAVY_STRIKE,
        /// Prefer a downward attack from above.
        AERIAL_STRIKE,
        /// No particular preference; fall back to normal selection.
        ANY
    }

    /// True when the move climbs enough to want the aerial state and animation rather than the flat one.
    public boolean isAerial() {
        return this.riseHeight >= 2.0;
    }

    // --- the moveset -------------------------------------------------------------------------------

    /// Short 45-90 degree arc to a new attack angle. The bread-and-butter reposition: cheap, frequent,
    /// and enough to break a player's rhythm without giving up pressure.
    public static final RepositionMove SIDE_STEP = new RepositionMove(
            "side_step", 70.0f, 13, 4.6, 3.9, 0.0, 26.0f, 0.0f, FollowUp.FAST_STRIKE);

    /// A wide 130-degree curve to the target's flank. Long enough to be a real angle change, short enough
    /// that the player can still track her the whole way.
    public static final RepositionMove FLANKING_ARC = new RepositionMove(
            "flanking_arc", 130.0f, 20, 5.4, 4.2, 0.4, 34.0f, 0.0f, FollowUp.FAST_STRIKE);

    /// All the way around to the target's back. Slower and wider, and she arrives set up for something
    /// heavy.
    public static final RepositionMove REAR_REPOSITION = new RepositionMove(
            "rear_reposition", 175.0f, 26, 5.8, 4.6, 0.5, 38.0f, 0.0f, FollowUp.HEAVY_STRIKE);

    /// A fast curved pass down the target's side, finishing behind them. More aggressive than a flank and
    /// it passes much closer — but it deals no damage. That is the whole distinction from Valkyrie's
    /// Passage, and it is why the player can learn to tell them apart.
    public static final RepositionMove REAR_PASS = new RepositionMove(
            "rear_pass", 160.0f, 16, 4.4, 2.9, 0.0, 46.0f, 6.0f, FollowUp.FAST_STRIKE);

    /// Sweeps upward and around, ending above and slightly behind the target — the setup for a downward
    /// attack. The ascent is curved, not a vertical elevator ride.
    public static final RepositionMove OVERHEAD_ARC = new RepositionMove(
            "overhead_arc", 120.0f, 24, 5.0, 2.6, 4.6, 30.0f, -22.0f, FollowUp.AERIAL_STRIKE);
}
