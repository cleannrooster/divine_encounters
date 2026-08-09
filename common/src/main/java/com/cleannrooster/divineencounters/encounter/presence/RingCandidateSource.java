package com.cleannrooster.divineencounters.encounter.presence;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/// Proposes ground positions on rings around the target, spread across all angles.
///
/// This is the arena-independent source: it needs nothing but a target, so a boss summoned with a
/// spawn egg in an open field still has somewhere to manifest. Angles are measured from the
/// target's own facing, so the same sample pattern automatically yields flank and rear options as
/// the player turns.
///
/// Positions are snapped down to the surface, because the entity that consumes this is a ground
/// predator — it should arrive standing on something, not hovering.
public final class RingCandidateSource implements CandidateSource {
    /// Sampled angles from the target's facing, mirrored to both sides. Chosen to cover the
    /// classic tactical positions — shoulder, side, rear-quarter, directly behind — rather than a
    /// uniform sweep, so every ring reliably contains a usable option of each type.
    private static final float[] ANGLES = {35.0f, 70.0f, 105.0f, 140.0f, 165.0f, 180.0f};
    private static final double[] RADII = {3.5, 5.0, 7.0, 9.5};

    @Override
    public void collect(CandidateContext context, List<Vec3> out) {
        var centre = context.targetPosition();
        var look = context.targetLook();

        for (var radius : RADII) {
            for (var angle : ANGLES) {
                // Both handednesses, except straight behind where they'd coincide.
                addRotated(context, out, centre, look, angle, radius);
                if (angle < 179.0f) {
                    addRotated(context, out, centre, look, -angle, radius);
                }
            }
        }
    }

    private static void addRotated(CandidateContext context, List<Vec3> out, Vec3 centre, Vec3 look,
                                   float angleDegrees, double radius) {
        var radians = Math.toRadians(angleDegrees);
        var cos = Math.cos(radians);
        var sin = Math.sin(radians);
        // Rotate the target's facing about the vertical axis.
        var direction = new Vec3(look.x * cos - look.z * sin, 0.0, look.x * sin + look.z * cos);
        var x = centre.x + direction.x * radius;
        var z = centre.z + direction.z * radius;
        var surface = context.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (int) Math.floor(x), (int) Math.floor(z));
        out.add(new Vec3(x, surface, z));
    }
}
