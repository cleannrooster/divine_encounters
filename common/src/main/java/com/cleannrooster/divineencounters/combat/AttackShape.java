package com.cleannrooster.divineencounters.combat;

/// The geometry families a {@link DirectionalAttack} can be built from.
///
/// Every shape is evaluated through the same parameterisation in {@link AttackGeometry}: a point on the
/// attack is `point(s, t)` where `s` is progress through the swing (0 = start of the sweep, 1 = end) and
/// `t` is distance along the blade (0 = the inner/root radius, 1 = the outer tip). Server hit detection
/// and client rendering both go through that one function, which is what keeps the visible shape and the
/// damaging shape the same object rather than two hand-tuned approximations.
public enum AttackShape {
    /// Crescent swept in the plane containing the facing vector and the attacker's right vector.
    /// Authored with `planeRoll = 0`; the sweep runs from one edge of the arc to the other.
    HORIZONTAL_ARC(Family.ARC),

    /// Crescent swept in the vertical plane (facing x up). Overhead and rising cuts.
    /// Authored with `planeRoll = 90`.
    VERTICAL_ARC(Family.ARC),

    /// Crescent swept in a tilted plane — the same maths as the other arcs, just a different `planeRoll`
    /// (typically 30-60 degrees). Kept as its own constant so definitions read clearly.
    DIAGONAL_ARC(Family.ARC),

    /// A narrow lane extending straight along the facing vector. The damaging end of the lane advances
    /// from the origin to the full range across the active window, so a thrust genuinely stabs outward
    /// instead of appearing all at once.
    THRUST_LANE(Family.LANE),

    /// A wide frontal wedge that resolves across its whole angular span in a single tick. Uses the same
    /// arc geometry, but every target inside the wedge is considered at once rather than being swept.
    FRONTAL_CONE(Family.ARC),

    /// A volume that travels with the attacker. Each tick sweeps a capsule from the attacker's previous
    /// position to its current one, so a dash damages everything it passes through without stopping.
    CHARGE_PATH(Family.PATH),

    /// A sphere centred on the attack origin — landing impacts, shockwaves, dive slams. Resolves once.
    RADIAL_IMPACT(Family.RADIAL);

    /// How a shape is evaluated, so callers can branch on behaviour rather than on every constant.
    public enum Family {
        ARC, LANE, PATH, RADIAL
    }

    private final Family family;

    AttackShape(Family family) {
        this.family = family;
    }

    public Family family() {
        return this.family;
    }

    /// True when the shape sweeps progressively across the active window (arcs and thrusts), false when it
    /// resolves its whole volume in one tick (cones, radial impacts).
    public boolean isSwept() {
        return this == HORIZONTAL_ARC || this == VERTICAL_ARC || this == DIAGONAL_ARC || this == THRUST_LANE;
    }

    /// True when the attack volume is re-anchored to the attacker every tick instead of being frozen at
    /// the origin captured when the attack committed.
    public boolean followsAttacker() {
        return this == CHARGE_PATH;
    }
}
