package com.cleannrooster.divineencounters.encounter.presence;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/// One place the entity *could* become real, with everything needed to judge it tactically.
///
/// While unresolved, a whole set of these is maintained and re-validated. Crucially none of them
/// is "the" position — the entity has no position — and one is only chosen at the moment something
/// forces it to become definite. That is what makes the uncertainty real in the combat model
/// rather than a hidden pre-picked teleport destination.
///
/// @param position       world position the entity would occupy (its feet)
/// @param facing         yaw, degrees, it would face on arrival — toward the target
/// @param distance       distance to the target, blocks
/// @param viewAngle      angle from the target's look direction, degrees; 0 is dead ahead of them
/// @param visible        whether any participating player can currently see this spot
/// @param staredAt       whether a player has been looking near this spot continuously — even if
///                       not strictly "visible", manifesting here would feel like a pop-in
/// @param valid          whether the entity physically fits here, clear of blocks and entities
/// @param elevated       whether this is above the target's footing (a perch)
/// @param cueOrigin      where an environmental cue for this candidate should play; usually the
///                       position itself, but an anchor may prefer a nearby cage rib
public record ManifestationCandidate(
        Vec3 position,
        float facing,
        double distance,
        float viewAngle,
        boolean visible,
        boolean staredAt,
        boolean valid,
        boolean elevated,
        Vec3 cueOrigin
) {
    /// Whether this candidate satisfies a requested kind's hard constraints. Scoring is separate —
    /// this is the pass/fail gate.
    public boolean satisfies(ManifestKind kind) {
        if (!this.valid) {
            return false;
        }
        if (kind.requiresElevation() && !this.elevated) {
            return false;
        }
        if (!kind.allowsVisible() && (this.visible || this.staredAt)) {
            // The no-visible-teleportation rule, enforced at selection time.
            return false;
        }
        if (this.distance < kind.minDistance() || this.distance > kind.maxDistance()) {
            return false;
        }
        return this.viewAngle >= kind.minViewAngle() && this.viewAngle <= kind.maxViewAngle();
    }

    /// How good this candidate is for a kind, higher is better. Only meaningful once
    /// {@link #satisfies} has passed.
    ///
    /// The shape of the score is what gives each kind its character: an ambush wants to be as far
    /// behind the target as possible at a striking distance, a frontal reveal wants to be squarely
    /// in front, a perch wants height.
    public double score(ManifestKind kind, double preferredDistance) {
        var angleScore = switch (kind) {
            // Deeper behind is strictly better.
            case REAR_AMBUSH -> this.viewAngle / 180.0;
            // Squarely in front is better.
            case FRONTAL_REVEAL -> 1.0 - this.viewAngle / 180.0;
            // Best at the middle of the allowed band — a true side angle.
            case FLANK -> 1.0 - Math.abs(this.viewAngle - 95.0) / 95.0;
            default -> 0.5;
        };
        // Closeness to the kind's ideal engagement range.
        var span = Math.max(1.0, kind.maxDistance() - kind.minDistance());
        var distanceScore = 1.0 - Math.abs(this.distance - preferredDistance) / span;
        var elevationBonus = this.elevated && kind.requiresElevation() ? 0.25 : 0.0;

        return Mth.clamp(angleScore, 0.0, 1.0) * 0.6
                + Mth.clamp(distanceScore, 0.0, 1.0) * 0.4
                + elevationBonus;
    }

    /// A copy with refreshed live judgements, for re-validating a maintained set each tick without
    /// rebuilding its geometry.
    public ManifestationCandidate withLiveState(boolean visible, boolean staredAt, boolean valid) {
        return new ManifestationCandidate(this.position, this.facing, this.distance, this.viewAngle,
                visible, staredAt, valid, this.elevated, this.cueOrigin);
    }
}
