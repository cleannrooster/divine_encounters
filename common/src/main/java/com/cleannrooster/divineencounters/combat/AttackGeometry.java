package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/// The one place attack geometry is defined.
///
/// Server hit detection, the client slash mesh and the particle emitter all call into this class, which
/// is what keeps them from drifting apart. There is no separate "hitbox" and "visual" — there is a swept
/// surface, and everything reads from it:
///
/// - `s` is progress through the swing, 0 at the start of the sweep and 1 at the end.
/// - `t` is distance along the blade, 0 at the inner radius and 1 at the outer reach.
///
/// {@link #surfacePoint} maps `(s, t)` to a world position for every shape family. Damage asks "is this
/// entity inside the slice of the surface swept since the last tick"; the renderer asks "where are the
/// corners of that same slice"; the emitter asks "give me a random point on it, biased toward the tip".
///
/// Everything is computed from the attacker's facing vector — no invisible marker entities, no
/// per-attack collision boxes to keep in sync.
public final class AttackGeometry {
    /// Nudge so degenerate (zero-length) attacks can't produce NaNs.
    private static final double EPSILON = 1.0e-6;

    private AttackGeometry() {
    }

    // --- frame capture ---------------------------------------------------------------------------

    /// Capture the frame for a swing about to commit: the swing's local yaw and pitch offsets applied to
    /// the attacker's facing, then the origin offset resolved against the resulting basis.
    ///
    /// Handedness biases the lateral offset, so a right-handed cut leaves the right shoulder even though
    /// the definition only states a magnitude.
    public static AttackFrame capture(DirectionalAttack def, LivingEntity attacker, boolean mirrored,
                                      float rollOffset) {
        var swing = def.swing();
        var yaw = Mth.wrapDegrees(attacker.getYRot() + swing.yawOffset());
        // Vertical aim comes from the attacker's own aim channel, not from getXRot().
        //
        // Vanilla's LookControl zeroes xRot at the start of every tick, after custom AI has run, so
        // an entity's pitch is not a value a boss can own — see AttackAim for the full explanation.
        // Attacks that shouldn't tilt at all (radial impacts) fall through to zero.
        var aim = attacker instanceof AttackAim.Aiming aiming && AttackAim.appliesTo(def)
                ? aiming.attackPitch()
                : attacker.getXRot();
        var pitch = Mth.wrapDegrees(aim + swing.pitchOffset());
        // A scaled body needs a scaled reach, or the arc detaches from the weapon that swung it.
        var scale = attacker.getScale();
        var base = new AttackFrame(attacker.position(), yaw, pitch, rollOffset, mirrored, scale);
        var lateral = def.offsetLateral() * swing.handedness().lateralSign() * (mirrored ? -1.0 : 1.0);
        var origin = attacker.position()
                .add(base.forward().scale(def.offsetForward() * scale))
                .add(base.right().scale(lateral * scale))
                .add(0.0, def.offsetVertical() * scale, 0.0);
        return base.withOrigin(origin);
    }

    // --- the swept surface -----------------------------------------------------------------------

    /// World position on the attack surface at swing progress `s` and blade fraction `t`.
    ///
    /// This is the function that makes the visual and the hitbox the same object. Everything that needs
    /// to know where an attack physically is calls here.
    public static Vec3 surfacePoint(DirectionalAttack def, AttackFrame frame, float s, float t) {
        var forward = frame.forward();
        return switch (def.shape().family()) {
            case ARC -> {
                var angle = Math.toRadians(sweepAngleDegrees(def, frame, s));
                var plane = planeAxis(def, frame);
                var direction = forward.scale(Math.cos(angle)).add(plane.scale(Math.sin(angle)));
                yield frame.origin().add(direction.scale(radiusAt(def, frame, t)));
            }
            // A thrust extends: at swing progress s the lane reaches s of the way out, and t runs along
            // whatever is currently extended. That puts t = 1 at the advancing point of the spear, which
            // is exactly where the density curve wants to pile particles.
            case LANE -> frame.origin().add(forward.scale((def.innerRadius()
                    + (def.range() - def.innerRadius()) * laneExtension(def, s) * t) * frame.scale()));
            // The path shape re-anchors its frame to the attacker every tick, so t simply runs backward
            // along the facing vector from the current position: t = 1 is the advancing front.
            case PATH -> frame.origin().add(forward.scale(
                    (def.range() * 0.5 * frame.scale()) * (t - 1.0)));
            // A radial impact has no swing direction, so s sweeps the full circle instead: the surface is
            // a disc of the shockwave's real radius, which is what the emitter needs to describe it.
            case RADIAL -> {
                var angle = Math.toRadians(s * 360.0);
                var plane = planeAxis(def, frame);
                var direction = forward.scale(Math.cos(angle)).add(plane.scale(Math.sin(angle)));
                yield frame.origin().add(direction.scale(radiusAt(def, frame, t)));
            }
        };
    }

    /// Angle of the swing at progress `s`, in degrees from the facing vector.
    ///
    /// The direction of travel comes from the definition's {@link SwingPath} — `s` always runs forward
    /// and the start/end angles carry the direction — so the arc sweeps the way the animation swings
    /// rather than the way the entity happens to be facing.
    public static float sweepAngleDegrees(DirectionalAttack def, AttackFrame frame, float s) {
        var angle = def.swing().angleAt(s);
        return frame.mirrored() ? -angle : angle;
    }

    /// The direction the strike is travelling at swing progress `s`.
    ///
    /// For an arc this is the tangent to the swing at that angle — the way the blade is actually
    /// moving, which for a horizontal sweep is sideways, not outward. For a lane or a charge it is
    /// simply the facing. Used to give knockback a direction of its own rather than always shoving
    /// radially away from the attacker.
    public static Vec3 strikeDirection(DirectionalAttack def, AttackFrame frame, float s) {
        return switch (def.shape().family()) {
            case ARC -> {
                var angle = Math.toRadians(sweepAngleDegrees(def, frame, s));
                var plane = planeAxis(def, frame);
                var tangent = frame.forward().scale(-Math.sin(angle)).add(plane.scale(Math.cos(angle)));
                // The blade may be travelling toward decreasing angles; the tangent above assumes
                // increasing. Mirroring flips the handedness a second time.
                var forwardTravel = def.swing().travelsPositive() != frame.mirrored();
                yield forwardTravel ? tangent : tangent.scale(-1.0);
            }
            case LANE, PATH, RADIAL -> frame.forward();
        };
    }

    /// How much of an attack's knockback should follow {@link #strikeDirection} rather than pushing
    /// radially away from the origin, 0-1.
    ///
    /// Derived from the shape rather than authored per attack, because the right answer is a property
    /// of what kind of motion the attack *is*:
    ///
    /// - **arcs** blend heavily — a cleave's whole character is lateral travel, and being thrown along
    ///   it is the difference between "swept aside" and "bumped";
    /// - **lanes and charges** blend further still; a lance drives you back down its own line;
    /// - **radial impacts** never blend. A shockwave has no direction but outward, and forcing one on
    ///   it would make the same explosion throw everyone the same way.
    public static double knockbackAlignment(DirectionalAttack def) {
        return switch (def.shape().family()) {
            case ARC -> 0.55;
            case LANE, PATH -> 0.75;
            case RADIAL -> 0.0;
        };
    }

    /// How far a lane has extended at swing progress `s`, 0-1.
    ///
    /// The arc family gets its speed distribution for free, because every consumer reaches the swing
    /// angle through {@link #sweepAngleDegrees}. Lanes have no angle to route through, so this is the
    /// equivalent funnel — called by both {@link #surfacePoint} and the lane hit test, so a thrust's
    /// drawn reach and its damaging reach extend at the same rate.
    public static float laneExtension(DirectionalAttack def, float s) {
        return def.swing().dynamics().apply(s);
    }

    /// Distance from the origin at blade fraction `t`, in the frame's scale.
    public static double radiusAt(DirectionalAttack def, AttackFrame frame, float t) {
        return (def.innerRadius() + (def.range() - def.innerRadius()) * Mth.clamp(t, 0.0f, 1.0f))
                * frame.scale();
    }

    /// The attack's outer reach at this frame's scale.
    public static double rangeOf(DirectionalAttack def, AttackFrame frame) {
        return def.range() * frame.scale();
    }

    /// Full lane/path width at this frame's scale.
    public static double widthOf(DirectionalAttack def, AttackFrame frame) {
        return def.width() * frame.scale();
    }

    /// Half-width of the ribbon perpendicular to the swing plane, for shapes that have one.
    public static double halfThickness(DirectionalAttack def, AttackFrame frame) {
        return def.verticalExtent() * 0.5 * frame.scale();
    }

    /// In-plane lateral axis for this attack: the direction positive swing angles point toward. Every
    /// caller — hit tests, ribbon, particles — goes through here so the plane can never be resolved two
    /// different ways.
    public static Vec3 planeAxis(DirectionalAttack def, AttackFrame frame) {
        var roll = def.planeRoll();
        return frame.planeAxis(frame.mirrored() ? -roll : roll);
    }

    /// Normal of the swing plane — the direction the damage volume has thickness in.
    public static Vec3 planeNormal(DirectionalAttack def, AttackFrame frame) {
        var roll = def.planeRoll();
        return frame.planeNormal(frame.mirrored() ? -roll : roll);
    }

    // --- broad phase -----------------------------------------------------------------------------

    /// A box guaranteed to contain the whole attack, used to pre-filter candidate victims.
    public static AABB bounds(DirectionalAttack def, AttackFrame frame, @Nullable Vec3 previousOrigin) {
        var pad = Math.max(def.width(), def.verticalExtent()) * 0.5 * frame.scale() + 1.0;
        return switch (def.shape().family()) {
            case ARC, RADIAL -> AABB.ofSize(frame.origin(),
                    rangeOf(def, frame) * 2 + pad, rangeOf(def, frame) * 2 + pad, rangeOf(def, frame) * 2 + pad);
            case LANE -> {
                var tip = frame.origin().add(frame.forward().scale(rangeOf(def, frame)));
                yield new AABB(frame.origin(), tip).inflate(pad);
            }
            case PATH -> {
                var from = previousOrigin != null ? previousOrigin : frame.origin();
                yield new AABB(from, frame.origin()).inflate(pad);
            }
        };
    }

    // --- hit detection ---------------------------------------------------------------------------

    /// Whether `victimBox` is inside the slice of the attack surface swept between progress `s0` and `s1`.
    ///
    /// Server-authoritative: this is the sole arbiter of whether an attack connects. It reads the same
    /// definition and frame the client renders from, so a player who dodges what they saw has dodged what
    /// actually existed.
    ///
    /// Takes a bare box rather than an entity so the geometry stays testable and reusable — nothing in
    /// here needs to know what it is hitting.
    ///
    /// @param previousOrigin the attacker's origin last tick, required only by {@link AttackShape#CHARGE_PATH}
    public static boolean hits(DirectionalAttack def, AttackFrame frame, float s0, float s1,
                               AABB victimBox, @Nullable Vec3 previousOrigin) {
        return switch (def.shape().family()) {
            case ARC -> hitsArc(def, frame, s0, s1, victimBox);
            case LANE -> hitsLane(def, frame, s1, victimBox);
            case PATH -> hitsPath(def, frame, victimBox, previousOrigin);
            case RADIAL -> hitsRadial(def, frame, victimBox);
        };
    }

    private static boolean hitsArc(DirectionalAttack def, AttackFrame frame, float s0, float s1,
                                   AABB victim) {
        var forward = frame.forward();
        var plane = planeAxis(def, frame);
        var normal = planeNormal(def, frame);

        var toVictim = victim.getCenter().subtract(frame.origin());
        var along = toVictim.dot(forward);
        var lateral = toVictim.dot(plane);
        var offPlane = toVictim.dot(normal);

        // Out of the swing plane by more than the volume's thickness plus the victim's own extent there.
        if (Math.abs(offPlane) > halfThickness(def, frame) + boxExtent(victim, normal)) {
            return false;
        }

        var radius = Math.sqrt(along * along + lateral * lateral);
        if (radius < EPSILON) {
            // Standing exactly on the origin: inside everything that isn't ring-shaped.
            return def.innerRadius() <= 0.5;
        }

        var radialDir = forward.scale(along / radius).add(plane.scale(lateral / radius));
        var radialPad = boxExtent(victim, radialDir);
        if (radius > rangeOf(def, frame) + radialPad
                || radius < def.innerRadius() * frame.scale() - radialPad) {
            return false;
        }

        // The angular window swept this tick, widened by how much of an angle the victim itself subtends
        // at this radius. Without that widening a fast sweep would tunnel straight past a nearby target.
        var tangent = forward.scale(-lateral / radius).add(plane.scale(along / radius));
        var angularPad = (float) Math.toDegrees(Math.atan2(boxExtent(victim, tangent), radius));

        var angle = (float) Math.toDegrees(Math.atan2(lateral, along));
        var a0 = sweepAngleDegrees(def, frame, s0);
        var a1 = sweepAngleDegrees(def, frame, s1);
        var lowest = Math.min(a0, a1) - angularPad;
        var highest = Math.max(a0, a1) + angularPad;
        return angle >= lowest && angle <= highest;
    }

    private static boolean hitsLane(DirectionalAttack def, AttackFrame frame, float extension,
                                    AABB victim) {
        var forward = frame.forward();
        var plane = planeAxis(def, frame);
        var normal = planeNormal(def, frame);

        var toVictim = victim.getCenter().subtract(frame.origin());
        var along = toVictim.dot(forward);
        var tip = radiusAt(def, frame, laneExtension(def, extension));

        if (along < def.innerRadius() * frame.scale() - boxExtent(victim, forward)
                || along > tip + boxExtent(victim, forward)) {
            return false;
        }
        if (Math.abs(toVictim.dot(plane)) > widthOf(def, frame) * 0.5 + boxExtent(victim, plane)) {
            return false;
        }
        return Math.abs(toVictim.dot(normal)) <= halfThickness(def, frame) + boxExtent(victim, normal);
    }

    /// A travelling attack: sweeps the segment covered since last tick, so a fast dash cannot skip past a
    /// player standing between the two sampled positions.
    private static boolean hitsPath(DirectionalAttack def, AttackFrame frame, AABB victim,
                                    @Nullable Vec3 previousOrigin) {
        var to = frame.origin();
        var from = previousOrigin != null ? previousOrigin : to;
        var padded = victim.inflate(widthOf(def, frame) * 0.5, halfThickness(def, frame), widthOf(def, frame) * 0.5);
        return padded.contains(to) || padded.contains(from) || padded.clip(from, to).isPresent();
    }

    private static boolean hitsRadial(DirectionalAttack def, AttackFrame frame, AABB victim) {
        var closest = closestPointOnBox(victim, frame.origin());
        var reach = rangeOf(def, frame);
        return closest.distanceToSqr(frame.origin()) <= reach * reach;
    }

    /// Half-extent of a bounding box projected onto `axis` — the correct padding to use when testing a
    /// box against a plane or a radius.
    private static double boxExtent(AABB box, Vec3 axis) {
        var hx = (box.maxX - box.minX) * 0.5;
        var hy = (box.maxY - box.minY) * 0.5;
        var hz = (box.maxZ - box.minZ) * 0.5;
        return Math.abs(axis.x) * hx + Math.abs(axis.y) * hy + Math.abs(axis.z) * hz;
    }

    private static Vec3 closestPointOnBox(AABB box, Vec3 point) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ));
    }

    // --- particle sampling -----------------------------------------------------------------------

    /// One sampled particle: where it goes, how it moves, and how far along the blade it landed (so the
    /// caller can tint or size it by position if it wants to).
    public record Sample(Vec3 position, Vec3 velocity, float bladeFraction) {
    }

    /// Draw a random point on the slice of the attack surface swept between `s0` and `s1`, distributed
    /// along the blade by the profile's density curve.
    ///
    /// Because `t` never leaves `[0, 1]` and `t = 1` is exactly the attack's real reach, particles
    /// physically cannot appear beyond the damaging volume. The visible falloff at the edge comes from
    /// the curve; the hard limit comes from the geometry.
    public static Sample sample(DirectionalAttack def, AttackFrame frame, float s0, float s1,
                                RandomSource random, @Nullable DensitySampler sampler) {
        var profile = def.particles();
        var density = sampler != null ? sampler : profile.sampler();
        var t = density.sample(random);
        var s = Mth.lerp(random.nextFloat(), s0, s1);

        var position = surfacePoint(def, frame, s, t);
        var outward = outwardDirection(def, frame, s, t);

        // Spread across the volume's thickness (and its width, for lanes) so the surface reads as solid.
        var normal = planeNormal(def, frame);
        var plane = planeAxis(def, frame);
        var thickness = halfThickness(def, frame);
        position = position.add(normal.scale((random.nextDouble() * 2.0 - 1.0) * thickness * 0.7));
        if (def.shape().family() == AttackShape.Family.LANE
                || def.shape().family() == AttackShape.Family.PATH) {
            position = position.add(plane.scale((random.nextDouble() * 2.0 - 1.0) * widthOf(def, frame) * 0.45));
        }
        if (profile.scatter() > 0.0f) {
            position = position.add(
                    (random.nextDouble() * 2.0 - 1.0) * profile.scatter(),
                    (random.nextDouble() * 2.0 - 1.0) * profile.scatter(),
                    (random.nextDouble() * 2.0 - 1.0) * profile.scatter());
        }

        var velocity = outward.scale(profile.speed()).add(
                (random.nextDouble() * 2.0 - 1.0) * profile.drift(),
                (random.nextDouble() * 2.0 - 1.0) * profile.drift(),
                (random.nextDouble() * 2.0 - 1.0) * profile.drift());
        return new Sample(position, velocity, t);
    }

    /// The direction "outward along the blade" at a point — particles drift along the swing rather than
    /// puffing in random directions.
    public static Vec3 outwardDirection(DirectionalAttack def, AttackFrame frame, float s, float t) {
        return switch (def.shape().family()) {
            case ARC -> {
                var angle = Math.toRadians(sweepAngleDegrees(def, frame, s));
                yield frame.forward().scale(Math.cos(angle))
                        .add(planeAxis(def, frame).scale(Math.sin(angle)));
            }
            case RADIAL -> {
                var angle = Math.toRadians(s * 360.0);
                yield frame.forward().scale(Math.cos(angle))
                        .add(planeAxis(def, frame).scale(Math.sin(angle)));
            }
            case LANE, PATH -> frame.forward();
        };
    }
}
