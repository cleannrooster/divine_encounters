package com.cleannrooster.divineencounters.combat;

import net.minecraft.world.phys.Vec3;

/// Everything about one particular swing that isn't in the shared {@link DirectionalAttack}: where it
/// started, which way it was aimed, and which way the arc travelled.
///
/// A frame is captured server-side the moment an attack commits, and is the only spatial data sent to
/// clients — nine floats and a flag. The client feeds it back into the same {@link AttackGeometry} the
/// server used for hit detection, so the arc drawn on screen is reconstructed from the damaging shape
/// rather than described separately.
///
/// The swing's *direction* is not in here — that belongs to the definition's {@link SwingPath}, so the
/// arc always travels the way its animation does. What the frame carries is only where and how the
/// attacker was standing.
///
/// @param origin     world position the attack radiates from (already includes the definition's offset)
/// @param yaw        facing yaw in degrees, MC convention, including the swing's yaw offset
/// @param pitch      facing pitch in degrees, MC convention, including the swing's pitch offset
/// @param rollOffset degrees added to the swing's `planeRoll`, for per-swing variation
/// @param mirrored   play the swing from the opposite side; only set when a caller explicitly asks for a
///                   mirrored variant, never at random
public record AttackFrame(Vec3 origin, float yaw, float pitch, float rollOffset, boolean mirrored,
                          float scale) {
    private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

    /// Facing vector.
    public Vec3 forward() {
        return Vec3.directionFromRotation(this.pitch, this.yaw);
    }

    /// The attacker's right-hand vector, perpendicular to {@link #forward()}.
    public Vec3 right() {
        var candidate = forward().cross(WORLD_UP);
        if (candidate.lengthSqr() < 1.0e-6) {
            // Aimed straight up or down: pick any stable perpendicular from the yaw alone.
            candidate = Vec3.directionFromRotation(0.0f, this.yaw).cross(WORLD_UP);
        }
        return candidate.normalize();
    }

    /// Completes the orthonormal basis.
    public Vec3 up() {
        return right().cross(forward()).normalize();
    }

    /// The in-plane lateral axis: rolling this around the facing vector is what turns one arc code path
    /// into horizontal, diagonal and vertical slashes.
    public Vec3 planeAxis(float planeRollDegrees) {
        var roll = Math.toRadians(planeRollDegrees + this.rollOffset);
        return right().scale(Math.cos(roll)).add(up().scale(Math.sin(roll))).normalize();
    }

    /// Normal of the swing plane — the direction the damage volume has thickness in.
    public Vec3 planeNormal(float planeRollDegrees) {
        var roll = Math.toRadians(planeRollDegrees + this.rollOffset);
        return right().scale(-Math.sin(roll)).add(up().scale(Math.cos(roll))).normalize();
    }

    /// Same frame relocated — used by {@link AttackShape#CHARGE_PATH}, whose volume follows the attacker.
    public AttackFrame withOrigin(Vec3 origin) {
        return new AttackFrame(origin, this.yaw, this.pitch, this.rollOffset, this.mirrored,
                this.scale);
    }
}
