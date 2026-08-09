package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.Mth;

/// Explicit description of how a swing travels through space, so the rendered arc can be authored to
/// match the weapon animation instead of being inferred from entity facing.
///
/// Before this existed, an arc was "half the span on each side of the facing vector" plus a per-swing
/// coin flip for direction — which meant a right-to-left animation could render a left-to-right arc.
/// Here the definition states the swing outright: where the blade starts, where it finishes, what plane
/// it travels through, and how that plane is oriented relative to the attacker.
///
/// ### Angle convention
/// Angles are degrees within the swing plane, measured from the attacker's facing vector. Positive is
/// toward the attacker's **right** at `planeRoll = 0`, and toward **up** at `planeRoll = 90` (the plane
/// axis rotates with the roll). So:
///
/// - right-to-left horizontal sweep: `start = +52, end = -52`
/// - left-to-right horizontal sweep: `start = -52, end = +52`
/// - overhead downward cut: roll 90, `start = +60, end = -60` (begins above, descends)
/// - rising slash: roll 90, `start = -50, end = +50`
///
/// `AttackGeometry` resolves damage against the same angles, so the arc a player sees swept is the arc
/// that hit them.
///
/// ### Animation correspondence
/// The placeholder animation clips are generated from these numbers by
/// `tools/gen_visage_animations.py`, so clip and arc travel the same way by construction. Retune the
/// angles here and re-run that script and they stay in step.
///
/// Note that the two conventions do not share a handedness: Bedrock bone rotations run the other way
/// once GeckoLib has loaded them, which the generator's `ANIM_YAW_SIGN` reconciles in one place. If a
/// swing ever reads backwards against its arc again, that constant is the fix — not mirroring the
/// angles here, which would move the damage as well as the visual.
///
/// @param plane        how the swing reads on screen; also supplies a default roll
/// @param startAngle   in-plane angle the swing begins at, degrees
/// @param endAngle     in-plane angle the swing finishes at, degrees
/// @param planeRoll    rotation of the swing plane about the facing vector, degrees
/// @param yawOffset    local yaw applied to the whole attack before anything else, degrees
/// @param pitchOffset  local pitch applied to the whole attack, degrees
/// @param handedness   which side of the attacker the blade comes from
/// @param dynamics     how the swing's speed is distributed across its active window
public record SwingPath(
        AttackPlane plane,
        float startAngle,
        float endAngle,
        float planeRoll,
        float yawOffset,
        float pitchOffset,
        Handedness handedness,
        SwingDynamics dynamics
) {
    /// Steady-speed swing — the default, and what every factory below produces. Attacks that want a
    /// forceful blade opt in with {@link #withDynamics}, so adding the concept changed no existing
    /// attack's behaviour.
    public SwingPath(AttackPlane plane, float startAngle, float endAngle, float planeRoll,
                     float yawOffset, float pitchOffset, Handedness handedness) {
        this(plane, startAngle, endAngle, planeRoll, yawOffset, pitchOffset, handedness,
                SwingDynamics.STEADY);
    }

    /// Angle at swing progress `s`, 0 at the start of the motion and 1 at the end. This single method is
    /// what makes the arc travel in the animated direction: `s` always runs forward, and the *angles*
    /// carry the direction.
    ///
    /// `s` is reshaped by the {@link SwingDynamics} curve first, which is what lets a blade accelerate
    /// through the middle of its arc. Because damage, the rendered ribbon and the particle sampler all
    /// read the swing angle through this one method, they accelerate together — the arc you see is
    /// still exactly the volume that can hit you, moving at exactly the speed it appears to.
    public float angleAt(float s) {
        return Mth.lerp(this.dynamics.apply(s), this.startAngle, this.endAngle);
    }

    /// Total angular span, always positive.
    public float sweepDegrees() {
        return Math.abs(this.endAngle - this.startAngle);
    }

    /// True when the blade travels toward the attacker's right (or upward, in a vertical plane).
    public boolean travelsPositive() {
        return this.endAngle >= this.startAngle;
    }

    /// A mirrored copy — the same motion from the other side. Used for authoring a left-handed variant of
    /// an attack as its own definition rather than randomising direction at runtime.
    public SwingPath mirrored() {
        return new SwingPath(this.plane, -this.startAngle, -this.endAngle, -this.planeRoll,
                -this.yawOffset, this.pitchOffset, this.handedness.opposite(), this.dynamics);
    }

    public SwingPath withYawOffset(float degrees) {
        return new SwingPath(this.plane, this.startAngle, this.endAngle, this.planeRoll,
                degrees, this.pitchOffset, this.handedness, this.dynamics);
    }

    public SwingPath withPitchOffset(float degrees) {
        return new SwingPath(this.plane, this.startAngle, this.endAngle, this.planeRoll,
                this.yawOffset, degrees, this.handedness, this.dynamics);
    }

    public SwingPath withHandedness(Handedness handedness) {
        return new SwingPath(this.plane, this.startAngle, this.endAngle, this.planeRoll,
                this.yawOffset, this.pitchOffset, handedness, this.dynamics);
    }

    /// Opt this swing into a speed distribution. See {@link SwingDynamics}.
    public SwingPath withDynamics(SwingDynamics dynamics) {
        return new SwingPath(this.plane, this.startAngle, this.endAngle, this.planeRoll,
                this.yawOffset, this.pitchOffset, this.handedness, dynamics);
    }

    // --- factories ---------------------------------------------------------------------------------

    /// Flat cut travelling right to left across the attacker's front.
    public static SwingPath rightToLeft(float arcDegrees) {
        return new SwingPath(AttackPlane.HORIZONTAL, arcDegrees * 0.5f, -arcDegrees * 0.5f,
                AttackPlane.HORIZONTAL.defaultRoll(), 0.0f, 0.0f, Handedness.RIGHT);
    }

    /// Flat cut travelling left to right.
    public static SwingPath leftToRight(float arcDegrees) {
        return new SwingPath(AttackPlane.HORIZONTAL, -arcDegrees * 0.5f, arcDegrees * 0.5f,
                AttackPlane.HORIZONTAL.defaultRoll(), 0.0f, 0.0f, Handedness.LEFT);
    }

    /// Overhead cut: starts raised and descends through the facing vector.
    public static SwingPath overhead(float arcDegrees, AttackPlane plane) {
        return new SwingPath(plane, arcDegrees * 0.5f, -arcDegrees * 0.5f,
                plane.defaultRoll(), 0.0f, 0.0f, Handedness.RIGHT);
    }

    /// Rising cut: starts low and sweeps upward.
    public static SwingPath rising(float arcDegrees, AttackPlane plane) {
        return new SwingPath(plane, -arcDegrees * 0.5f, arcDegrees * 0.5f,
                plane.defaultRoll(), 0.0f, 0.0f, Handedness.RIGHT);
    }

    /// A descending diagonal at an explicit plane roll — high on the attacker's right, finishing low on
    /// the left.
    public static SwingPath diagonalDescending(float arcDegrees, float planeRoll) {
        return new SwingPath(AttackPlane.CUSTOM, arcDegrees * 0.5f, -arcDegrees * 0.5f,
                planeRoll, 0.0f, 0.0f, Handedness.RIGHT);
    }

    /// A symmetric span with no travel — for cones and impacts, which resolve everywhere at once. The
    /// renderer still animates the reveal, but there is no directional motion to contradict.
    public static SwingPath fixedSpan(float arcDegrees) {
        return new SwingPath(AttackPlane.HORIZONTAL, -arcDegrees * 0.5f, arcDegrees * 0.5f,
                0.0f, 0.0f, 0.0f, Handedness.CENTRED);
    }

    /// For lanes and charge paths: no angular travel, but the plane roll still orients the volume (and
    /// therefore the ribbon) — a roll near 90 turns a flat lane into a tall vertical blade.
    public static SwingPath lane(float planeRoll) {
        return new SwingPath(AttackPlane.CUSTOM, 0.0f, 0.0f, planeRoll, 0.0f, 0.0f, Handedness.CENTRED);
    }
}
