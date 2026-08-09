package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/// Standalone verification of the directional-attack geometry, run from the `geometryCheck` Gradle task.
///
/// It asserts the two properties the whole combat system rests on: that an attack damages exactly the
/// volume its numbers describe, and that particles are weighted toward the tip while never escaping the
/// real reach. Useful whenever the numbers in `VisageAttacks` are retuned.
public final class GeometryCheck {
    private static int failures;

    public static void main(String[] args) {
        var origin = new Vec3(0, 1, 0);
        // Facing +Z (yaw 0).
        var frame = new AttackFrame(origin, 0.0f, 0.0f, 0.0f, false, 1.0f);
        System.out.printf("forward=%s right=%s up=%s%n", frame.forward(), frame.right(), frame.up());

        // --- horizontal arc: 105 degrees, reach 3.9 -----------------------------------------
        var sweep = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "sweep"),
                        AttackShape.HORIZONTAL_ARC)
                .range(3.9).innerRadius(0.9).arc(105.0f).verticalExtent(2.4).build();

        expect("tip of sweep is at full reach",
                near(AttackGeometry.surfacePoint(sweep, frame, 0.5f, 1.0f).distanceTo(origin), 3.9));
        expect("root of sweep is at inner radius",
                near(AttackGeometry.surfacePoint(sweep, frame, 0.5f, 0.0f).distanceTo(origin), 0.9));
        expect("s=0.5 points straight ahead",
                near(AttackGeometry.surfacePoint(sweep, frame, 0.5f, 1.0f).z - origin.z, 3.9));
        expect("sweep stays horizontal",
                near(AttackGeometry.surfacePoint(sweep, frame, 0.0f, 1.0f).y, origin.y));

        expect("target dead ahead at 3 blocks is hit mid-swing",
                AttackGeometry.hits(sweep, frame, 0.45f, 0.55f, box(0, 1, 3), null));
        expect("target dead ahead is missed at the start of the swing",
                !AttackGeometry.hits(sweep, frame, 0.0f, 0.1f, box(0, 1, 3), null));
        expect("target beyond reach is missed",
                !AttackGeometry.hits(sweep, frame, 0.0f, 1.0f, box(0, 1, 5.5), null));
        expect("target behind is missed",
                !AttackGeometry.hits(sweep, frame, 0.0f, 1.0f, box(0, 1, -3), null));
        expect("target far above is missed",
                !AttackGeometry.hits(sweep, frame, 0.0f, 1.0f, box(0, 5, 3), null));
        expect("target at the arc edge is hit",
                AttackGeometry.hits(sweep, frame, 0.0f, 1.0f, box(-2.0, 1, 2.0), null));
        expect("target past the arc edge is missed",
                !AttackGeometry.hits(sweep, frame, 0.0f, 1.0f, box(-3.0, 1, 0.6), null));

        // --- vertical arc: plane roll swaps the sweep into the vertical -----------------------
        var vertical = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "vertical"),
                        AttackShape.VERTICAL_ARC)
                .range(4.0).innerRadius(0.5).arc(120.0f).verticalExtent(2.0).build();
        var high = AttackGeometry.surfacePoint(vertical, frame, 1.0f, 1.0f);
        var low = AttackGeometry.surfacePoint(vertical, frame, 0.0f, 1.0f);
        expect("vertical arc sweeps through Y", Math.abs(high.y - low.y) > 4.0);
        expect("vertical arc barely moves in X", Math.abs(high.x - low.x) < 0.1);

        // --- thrust lane ----------------------------------------------------------------------
        var thrust = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "thrust"),
                        AttackShape.THRUST_LANE)
                .range(5.6).innerRadius(0.8).width(1.8).verticalExtent(1.9).build();
        expect("thrust reaches full length only at full extension",
                near(AttackGeometry.surfacePoint(thrust, frame, 1.0f, 1.0f).z, 5.6));
        expect("half-extended thrust reaches half way",
                near(AttackGeometry.surfacePoint(thrust, frame, 0.5f, 1.0f).z, 0.8 + 4.8 * 0.5));
        expect("target at 4 blocks missed while half extended",
                !AttackGeometry.hits(thrust, frame, 0.0f, 0.5f, box(0, 1, 4.5), null));
        expect("target at 4 blocks hit once fully extended",
                AttackGeometry.hits(thrust, frame, 0.0f, 1.0f, box(0, 1, 4.5), null));
        expect("target off to the side of the lane is missed",
                !AttackGeometry.hits(thrust, frame, 0.0f, 1.0f, box(2.5, 1, 4.0), null));
        expect("target past the lane is missed",
                !AttackGeometry.hits(thrust, frame, 0.0f, 1.0f, box(0, 1, 7.0), null));

        // --- Heaven's Divide: rolled lane should be tall and narrow ---------------------------
        var divide = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "divide"),
                        AttackShape.THRUST_LANE)
                .planeRoll(78.0f).range(11.0).innerRadius(1.0).width(5.0).verticalExtent(2.6).build();
        expect("divide reaches 11 blocks",
                AttackGeometry.hits(divide, frame, 0.0f, 1.0f, box(0, 1, 10.5), null));
        expect("divide misses past 11 blocks",
                !AttackGeometry.hits(divide, frame, 0.0f, 1.0f, box(0, 1, 12.5), null));
        expect("divide is narrow horizontally",
                !AttackGeometry.hits(divide, frame, 0.0f, 1.0f, box(3.0, 1, 6.0), null));
        expect("divide is tall vertically",
                AttackGeometry.hits(divide, frame, 0.0f, 1.0f, box(0, 3.0, 6.0), null));

        // --- charge path ----------------------------------------------------------------------
        var charge = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "charge"),
                        AttackShape.CHARGE_PATH)
                .range(5.0).width(2.4).verticalExtent(2.8).build();
        var moved = frame.withOrigin(new Vec3(0, 1, 6));
        expect("charge sweeps everything between last tick and this one",
                AttackGeometry.hits(charge, moved, 0.0f, 1.0f, box(0, 1, 3), new Vec3(0, 1, 0)));
        expect("charge misses to the side",
                !AttackGeometry.hits(charge, moved, 0.0f, 1.0f, box(4, 1, 3), new Vec3(0, 1, 0)));

        // --- swing direction must match what the definition declares -------------------------
        // This is the property that keeps a rendered arc attached to its animation: `s` runs forward and
        // the start/end angles carry the direction, so a right-to-left cut can never draw left-to-right.
        var rightToLeft = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "r2l"),
                        AttackShape.HORIZONTAL_ARC)
                .swing(SwingPath.rightToLeft(105.0f))
                .range(4.0).innerRadius(0.8).verticalExtent(2.4).build();
        var leftToRight = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "l2r"),
                        AttackShape.HORIZONTAL_ARC)
                .swing(SwingPath.leftToRight(105.0f))
                .range(4.0).innerRadius(0.8).verticalExtent(2.4).build();

        // Facing +Z (south), her right hand points to -X.
        var onHerRight = box(-2.4, 1, 2.0);
        var onHerLeft = box(2.4, 1, 2.0);

        expect("right-to-left starts on her right",
                AttackGeometry.sweepAngleDegrees(rightToLeft, frame, 0.0f) > 0.0f);
        expect("right-to-left finishes on her left",
                AttackGeometry.sweepAngleDegrees(rightToLeft, frame, 1.0f) < 0.0f);
        expect("right-to-left hits her right side early",
                AttackGeometry.hits(rightToLeft, frame, 0.0f, 0.2f, onHerRight, null));
        expect("right-to-left has not reached her left side early",
                !AttackGeometry.hits(rightToLeft, frame, 0.0f, 0.2f, onHerLeft, null));
        expect("right-to-left hits her left side late",
                AttackGeometry.hits(rightToLeft, frame, 0.8f, 1.0f, onHerLeft, null));

        expect("left-to-right starts on her left",
                AttackGeometry.sweepAngleDegrees(leftToRight, frame, 0.0f) < 0.0f);
        expect("left-to-right hits her left side early",
                AttackGeometry.hits(leftToRight, frame, 0.0f, 0.2f, onHerLeft, null));
        expect("left-to-right has not reached her right side early",
                !AttackGeometry.hits(leftToRight, frame, 0.0f, 0.2f, onHerRight, null));

        // Overhead cut: the blade must physically start high and finish low.
        var overhead = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "overhead"),
                        AttackShape.VERTICAL_ARC)
                .swing(SwingPath.overhead(120.0f, AttackPlane.VERTICAL))
                .range(4.0).innerRadius(0.6).verticalExtent(2.0).build();
        var bladeStart = AttackGeometry.surfacePoint(overhead, frame, 0.0f, 1.0f);
        var bladeEnd = AttackGeometry.surfacePoint(overhead, frame, 1.0f, 1.0f);
        expect("overhead cut begins above the origin", bladeStart.y > origin.y + 1.0);
        expect("overhead cut ends below the origin", bladeEnd.y < origin.y - 1.0);

        var rising = DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "rising"),
                        AttackShape.VERTICAL_ARC)
                .swing(SwingPath.rising(120.0f, AttackPlane.VERTICAL))
                .range(4.0).innerRadius(0.6).verticalExtent(2.0).build();
        expect("rising cut travels upward",
                AttackGeometry.surfacePoint(rising, frame, 1.0f, 1.0f).y
                        > AttackGeometry.surfacePoint(rising, frame, 0.0f, 1.0f).y);

        // The moveset's own definitions, so a retune can't silently reverse a swing.
        expect("Wing Sweep is authored right-to-left",
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.WING_SWEEP
                        .swing().startAngle() > 0.0f);
        expect("Sundering Sweep is authored left-to-right",
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.SUNDERING_SWEEP
                        .swing().startAngle() < 0.0f);
        expect("Descending Cut starts high",
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.DESCENDING_CUT
                        .swing().startAngle() > 0.0f);

        // --- Malice's moveset: same guarantees, opposite palette ------------------------------
        expect("Hooking Swipe is authored left-to-right",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.HOOKING_SWIPE
                        .swing().startAngle() < 0.0f);
        expect("Crescent of Spite is authored left-to-right",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.CRESCENT_OF_SPITE
                        .swing().startAngle() < 0.0f);
        expect("Black Sweep is authored right-to-left",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.BLACK_SWEEP
                        .swing().startAngle() > 0.0f);
        expect("Crescent of Spite leaves a safe rear angle",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.CRESCENT_OF_SPITE
                        .arcDegrees() < 180.0f);

        // Needle Thrust must be genuinely narrow — it is the precision attack, and a wide one would
        // make flank manifestations unfair.
        var needle = com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.NEEDLE_THRUST;
        expect("Needle Thrust hits a target dead ahead",
                AttackGeometry.hits(needle, frame, 0.0f, 1.0f, box(0, 1, 3.0), null));
        expect("Needle Thrust misses a target a step to the side",
                !AttackGeometry.hits(needle, frame, 0.0f, 1.0f, box(1.6, 1, 3.0), null));
        expect("Needle Thrust misses past its reach",
                !AttackGeometry.hits(needle, frame, 0.0f, 1.0f, box(0, 1, 6.0), null));

        // Every ambush attack must declare the manifestation category it needs, or the fairness
        // chain has no category to resolve and the attack would silently never fire.
        for (var attack : new com.cleannrooster.divineencounters.combat.DirectionalAttack[]{
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.BACKBITE,
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.POUNCE_STRIKE,
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.BLACK_SWEEP}) {
            expect(attack.id().getPath() + " declares a manifestation kind",
                    com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks
                            .ambushKind(attack) != null);
        }
        expect("Backbite manifests behind the target",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.ambushKind(
                        com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.BACKBITE)
                        == com.cleannrooster.divineencounters.encounter.presence.ManifestKind.REAR_AMBUSH);
        expect("Pounce Strike needs a perch",
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.ambushKind(
                        com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.POUNCE_STRIKE)
                        == com.cleannrooster.divineencounters.encounter.presence.ManifestKind.PERCH);
        expect("ordinary attacks need no manifestation",
                !com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.requiresAmbush(
                        com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.HOOKING_SWIPE));

        // --- enlarged visuals must still not escape the damage volume ------------------------
        // The arcs were made substantially bigger by filling the true geometry rather than by
        // exceeding it. These assertions are what keep that true: a profile is free to be as loud
        // as it likes, but the drawn ribbon has to stay inside the volume that can actually hit.
        var visualAttacks = new com.cleannrooster.divineencounters.combat.DirectionalAttack[]{
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.WING_SWEEP,
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.SUNDERING_SWEEP,
                com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks.LANCE_THRUST,
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.HOOKING_SWIPE,
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.CRESCENT_OF_SPITE,
                com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks.NEEDLE_THRUST};

        for (var attack : visualAttacks) {
            var profile = attack.slash();
            var name = attack.id().getPath();
            expect(name + " draws no further out than its real reach",
                    profile.innerFraction() >= 0.0f && profile.innerFraction() < 1.0f);
            expect(name + " shows no more arc than it sweeps", profile.trail() <= 1.0f);
            // The renderer clamps thickness to the half-extent; assert the clamp actually bites, so
            // a profile authored too thick is corrected rather than sticking out of the hitbox.
            var drawn = Math.min(profile.thickness(), (float) AttackGeometry.halfThickness(attack, frame));
            expect(name + " slab stays inside the damage thickness",
                    drawn <= AttackGeometry.halfThickness(attack, frame) + 1.0e-4);

            // The outermost drawn point must coincide with the attack's own tip.
            var tip = AttackGeometry.surfacePoint(attack, frame, 0.5f, 1.0f);
            expect(name + " ribbon tip sits exactly at full reach",
                    Math.abs(tip.distanceTo(origin) - attack.range()) < 0.05
                            || attack.shape().family() != AttackShape.Family.ARC);
        }

        // The enlarged profiles must genuinely be larger than what they replaced.
        var sweepProfile = com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks
                .WING_SWEEP.slash();
        expect("crescents now show most of the swept arc", sweepProfile.trail() >= 0.85f);
        expect("crescents now start near the hilt", sweepProfile.innerFraction() <= 0.1f);
        expect("crescents now have body", sweepProfile.thickness() > 0.0f);

        // Each boss's edge layer must exist and behave in character.
        var warEdge = com.cleannrooster.divineencounters.content.visage.ai.VisageAttacks
                .WING_SWEEP.particles().edge();
        var maliceEdge = com.cleannrooster.divineencounters.content.malice.ai.MaliceAttacks
                .HOOKING_SWIPE.particles().edge();
        expect("War's arcs carry an edge layer", warEdge != null);
        expect("Malice's arcs carry an edge layer", maliceEdge != null);
        if (warEdge != null && maliceEdge != null) {
            expect("War's edge forks into bolts", warEdge.bolt() > 0.0f);
            expect("War's edge is violent", warEdge.chaos() > maliceEdge.chaos());
            expect("Malice's edge drifts upward instead", maliceEdge.lift() > 0.0f);
            expect("Malice's edge does not fork", maliceEdge.bolt() == 0.0f);
            expect("both edges stay in the outer band of the blade",
                    warEdge.band() <= 0.5f && maliceEdge.band() <= 0.5f);
        }

        // --- a scaled body must scale its reach with it ---------------------------------------
        // The bosses render at 1.6x through the vanilla scale attribute. If geometry ignored that,
        // a boss twice the size would swing a normal-length arc out of its shins — visual and
        // damage would still agree with each other, but neither would agree with the body.
        var bigFrame = new AttackFrame(origin, 0.0f, 0.0f, 0.0f, false, 1.6f);
        expect("a scaled frame scales the reach",
                Math.abs(AttackGeometry.rangeOf(sweep, bigFrame) - sweep.range() * 1.6) < 1.0e-4);
        expect("a scaled frame scales the blade",
                Math.abs(AttackGeometry.surfacePoint(sweep, bigFrame, 0.5f, 1.0f).distanceTo(origin)
                        - sweep.range() * 1.6) < 0.05);
        expect("a scaled frame scales lane width",
                Math.abs(AttackGeometry.widthOf(thrust, bigFrame) - thrust.width() * 1.6) < 1.0e-4);
        expect("a scaled frame scales thickness",
                Math.abs(AttackGeometry.halfThickness(sweep, bigFrame)
                        - sweep.verticalExtent() * 0.5 * 1.6) < 1.0e-4);
        // Hit detection must follow, or the drawn arc would out-reach the damage.
        expect("a scaled sweep reaches further",
                AttackGeometry.hits(sweep, bigFrame, 0.4f, 0.6f, box(0, 1, 5.4), null));
        expect("an unscaled sweep does not reach that far",
                !AttackGeometry.hits(sweep, frame, 0.4f, 0.6f, box(0, 1, 5.4), null));
        expect("a scaled thrust still misses beyond its scaled reach",
                !AttackGeometry.hits(thrust, bigFrame, 0.0f, 1.0f, box(0, 1, 5.6 * 1.6 + 1.5), null));

        // --- particle density: mass must sit near the tip, and never past it ------------------
        var random = RandomSource.create(42L);
        var sampler = new DensitySampler(DensityCurve.leadingEdge(2.6f, 0.9f));
        var buckets = new int[5];
        var max = 0.0f;
        for (var i = 0; i < 200_000; i++) {
            var t = sampler.sample(random);
            max = Math.max(max, t);
            buckets[Math.min(4, (int) (t * 5))]++;
        }
        System.out.printf("density buckets (root->tip): %d %d %d %d %d, max t = %.4f%n",
                buckets[0], buckets[1], buckets[2], buckets[3], buckets[4], max);
        expect("density never leaves [0,1]", max <= 1.0f);
        expect("outer fifth is the densest band", buckets[4] > buckets[0] * 4);
        expect("density increases monotonically outward",
                buckets[0] < buckets[1] && buckets[1] < buckets[2] && buckets[2] < buckets[3]);

        // Every sampled particle must land inside the damage volume.
        var outside = 0;
        for (var i = 0; i < 20_000; i++) {
            var sample = AttackGeometry.sample(sweep, frame, 0.0f, 1.0f, random, null);
            if (sample.position().distanceTo(origin) > sweep.range() + 0.35) {
                outside++;
            }
        }
        System.out.printf("particles beyond reach + scatter: %d / 20000%n", outside);
        expect("particles stay within the real reach", outside == 0);

        checkSwingDynamics();
        checkOverreachIsCosmetic(sweep, frame);
        checkStrikeDirection(sweep, frame);
        checkAimCurve();
        checkAimReachesGeometry(sweep, origin);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /// The vertical aim curve.
    ///
    /// Two things need locking down. The cap is a hard promise — an attack must never tilt past 45°
    /// however extreme the geometry — and the curve must stay faithful near level, because that is
    /// where almost every swing in a real fight happens. A curve that hit the cap correctly but
    /// mushed the common case would be worse than no curve at all.
    private static void checkAimCurve() {
        expect("level targets produce no tilt", near(AttackAim.curve(0.0f), 0.0));

        var monotonic = true;
        var capped = true;
        var previous = -AttackAim.MAX_PITCH;
        for (var raw = -180; raw <= 180; raw++) {
            var value = AttackAim.curve(raw);
            if (value < previous - 1.0e-4f) {
                monotonic = false;
            }
            if (Math.abs(value) > AttackAim.MAX_PITCH + 1.0e-4f) {
                capped = false;
            }
            previous = value;
        }
        expect("aim never exceeds the 45 degree cap", capped);
        expect("aim rises monotonically with the true angle", monotonic);

        expect("aiming up and down are symmetric",
                near(AttackAim.curve(30.0f), -AttackAim.curve(-30.0f)));

        // Faithful where it matters: a target a few degrees off level should barely be compressed.
        expect("small angles are tracked almost exactly",
                Math.abs(AttackAim.curve(5.0f) - 5.0f) < 0.15f);
        expect("moderate angles are tracked closely",
                Math.abs(AttackAim.curve(15.0f) - 15.0f) < 1.2f);

        // And progressively compressed beyond that, without ever flattening out entirely.
        System.out.printf("aim curve: 30->%.1f  45->%.1f  70->%.1f  90->%.1f%n",
                AttackAim.curve(30.0f), AttackAim.curve(45.0f),
                AttackAim.curve(70.0f), AttackAim.curve(90.0f));
        expect("steep angles are compressed", AttackAim.curve(45.0f) < 40.0f);
        expect("very steep angles still approach the cap", AttackAim.curve(90.0f) > 40.0f);
        expect("the response never dies completely",
                AttackAim.curve(90.0f) - AttackAim.curve(70.0f) > 0.3f);

        // Radial impacts opt out entirely — a tilted ground slam is unreadable.
        expect("radial impacts are excluded from aiming", !AttackAim.appliesTo(impactFor()));
        expect("arcs are aimed", AttackAim.appliesTo(
                DirectionalAttack.builder(
                                net.minecraft.resources.ResourceLocation
                                        .fromNamespaceAndPath("test", "aimed"),
                                AttackShape.HORIZONTAL_ARC)
                        .range(4.0).innerRadius(0.8).verticalExtent(2.0).build()));
    }

    /// The aim must actually move the damage volume, not merely the drawing of it.
    ///
    /// This is the regression that started the whole change: the pitch was being computed correctly
    /// and thrown away, so attacks resolved as though every target were exactly level. Asserting the
    /// curve alone would not have caught that — the curve was never the broken part. What matters is
    /// that a frame built with a tilt reaches higher, and one built level does not.
    private static void checkAimReachesGeometry(DirectionalAttack sweep, Vec3 origin) {
        var level = new AttackFrame(origin, 0.0f, 0.0f, 0.0f, false, 1.0f);
        var aimedUp = new AttackFrame(origin, 0.0f, -35.0f, 0.0f, false, 1.0f);
        var aimedDown = new AttackFrame(origin, 0.0f, 35.0f, 0.0f, false, 1.0f);

        // Far enough above to be outside the swing's own vertical tolerance.
        //
        // The sweep's verticalExtent of 2.4 gives it 1.2 of half-thickness, and a player box adds
        // another 0.9 — so anything within 2.1 of level is hit *without* any aiming at all, and a
        // test placed there would pass whether the aim worked or not. 3.0 clears it honestly.
        var above = box(0, origin.y + 3.0, 2.6);
        expect("a level swing misses a target above",
                !AttackGeometry.hits(sweep, level, 0.0f, 1.0f, above, null));
        expect("aiming up hits a target above",
                AttackGeometry.hits(sweep, aimedUp, 0.0f, 1.0f, above, null));

        var below = box(0, origin.y - 3.0, 2.6);
        expect("a level swing misses a target below",
                !AttackGeometry.hits(sweep, level, 0.0f, 1.0f, below, null));
        expect("aiming down hits a target below",
                AttackGeometry.hits(sweep, aimedDown, 0.0f, 1.0f, below, null));

        // Tilting must not cost her the level target she could already reach.
        var ahead = box(0, origin.y, 3.0);
        expect("a level target is still hit while aimed up",
                AttackGeometry.hits(sweep, aimedUp, 0.0f, 1.0f, ahead, null));
    }

    /// The overreach bloom must be cosmetic, and provably so.
    ///
    /// The bloom is the only geometry in the mod drawn outside the damage volume, which makes it the
    /// only place where "the arc you see is the arc that hits you" could quietly stop being true. It
    /// is a rendering-only concept — the hit tests never read `overreach` at all — so what this
    /// asserts is that the property still holds after the change: a victim standing anywhere in the
    /// blooming region, at any point in the swing, is not hit.
    ///
    /// If this ever fails, the fix is not to adjust the bloom. It means something started feeding the
    /// profile's overreach into geometry, and the honest arc has become a lie.
    private static void checkOverreachIsCosmetic(DirectionalAttack sweep, AttackFrame frame) {
        var profile = com.cleannrooster.divineencounters.combat.AttackVisuals.heavyCrescent();
        expect("the heavy crescent actually declares a bloom", profile.hasOverreach());

        // Start beyond the victim's own half-extent, not merely beyond the reach.
        //
        // The hit test widens the volume by how much of the victim's box lies along the radius —
        // correctly, since a player whose body overlaps the swing should be hit even if their centre
        // is outside it. A box centred a hair past maximum reach still overlaps, so testing from
        // `range` would be measuring that padding rather than the bloom. Half of a 0.6-wide box on a
        // diagonal reaches ~0.42, so 0.45 clears it.
        var range = sweep.range();
        var bloomInner = range + 0.45;
        var bloomOuter = range * (1.0 + profile.overreach());
        expect("the bloom still extends past the padded hitbox", bloomOuter > bloomInner);

        var hitInBloom = 0;
        for (var step = 0; step <= 40; step++) {
            var t = step / 40.0;
            var distance = Mth.lerp(t, bloomInner, bloomOuter);
            // Sweep the whole arc, and the whole swing, at each distance.
            for (var a = -60; a <= 60; a += 5) {
                var radians = Math.toRadians(a);
                var x = Math.sin(radians) * distance;
                var z = Math.cos(radians) * distance;
                if (AttackGeometry.hits(sweep, frame, 0.0f, 1.0f,
                        box(x, frame.origin().y, z), null)) {
                    hitInBloom++;
                }
            }
        }
        System.out.printf("victims hit inside the bloom: %d%n", hitInBloom);
        expect("nothing in the overreach bloom can be damaged", hitInBloom == 0);
    }

    /// Knockback direction.
    ///
    /// The point of blending in the strike's travel vector is that a cleave throws you *along* its
    /// arc rather than straight outward, so the two directions had better actually differ — a tangent
    /// that came out parallel to the radius would mean the blend does nothing and the whole change is
    /// decorative.
    private static void checkStrikeDirection(DirectionalAttack sweep, AttackFrame frame) {
        var mid = AttackGeometry.strikeDirection(sweep, frame, 0.5f);
        expect("strike direction is a unit vector", near(mid.length(), 1.0));

        // At the middle of a symmetric sweep the blade points along the facing, so its travel should
        // be square to it.
        var radial = frame.forward();
        expect("a sweep travels across its own reach, not along it",
                Math.abs(mid.dot(radial)) < 0.05);

        // And it must reverse when the swing does.
        var mirrored = new AttackFrame(frame.origin(), frame.yaw(), frame.pitch(),
                frame.rollOffset(), true, frame.scale());
        var mirroredMid = AttackGeometry.strikeDirection(sweep, mirrored, 0.5f);
        expect("mirroring the swing reverses the direction of the force",
                mid.dot(mirroredMid) < -0.9);

        expect("radial impacts never align knockback to a direction",
                AttackGeometry.knockbackAlignment(impactFor()) == 0.0);
    }

    private static DirectionalAttack impactFor() {
        return DirectionalAttack.builder(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "impact"),
                        AttackShape.RADIAL_IMPACT)
                .range(4.0).innerRadius(0.0).verticalExtent(2.0).build();
    }

    /// The {@link SwingDynamics} contract.
    ///
    /// These curves reshape swing progress for the damage sweep as well as the visuals, so a curve
    /// that misbehaves does not merely look wrong — it moves the hitbox. Three properties matter:
    ///
    /// - **endpoints are fixed**, so an attack still sweeps exactly its declared arc in exactly its
    ///   declared number of ticks. A curve that undershoots 1 would quietly shorten every swing that
    ///   used it;
    /// - **monotonic**, because the damage sweep tests the angular window between the previous tick's
    ///   progress and this one's. A curve that ever runs backwards would drag that window back over
    ///   ground it already covered and hit through it a second time;
    /// - **bounded**, so no curve can push the blade past its end angle even momentarily.
    ///
    /// FORCEFUL is additionally asserted to actually be forceful — front-loading nothing and covering
    /// most of its arc in the middle third — because a curve that satisfies the contract but is
    /// visually indistinguishable from STEADY would pass silently and do nothing.
    private static void checkSwingDynamics() {
        for (var dynamics : SwingDynamics.values()) {
            expect(dynamics + " starts at 0", near(dynamics.apply(0.0f), 0.0));
            expect(dynamics + " ends at 1", near(dynamics.apply(1.0f), 1.0));

            var monotonic = true;
            var bounded = true;
            var previous = 0.0f;
            for (var step = 0; step <= 400; step++) {
                var value = dynamics.apply(step / 400.0f);
                if (value < previous - 1.0e-6f) {
                    monotonic = false;
                }
                if (value < -1.0e-6f || value > 1.0f + 1.0e-6f) {
                    bounded = false;
                }
                previous = value;
            }
            expect(dynamics + " never runs backwards", monotonic);
            expect(dynamics + " stays within [0, 1]", bounded);

            // Overshooting the window must not push the blade past its declared end angle.
            expect(dynamics + " clamps past the end of the swing", near(dynamics.apply(1.4f), 1.0));
            expect(dynamics + " clamps before the start", near(dynamics.apply(-0.3f), 0.0));
        }

        // The middle third of a FORCEFUL swing should carry most of the arc.
        var middle = SwingDynamics.FORCEFUL.apply(0.667f) - SwingDynamics.FORCEFUL.apply(0.333f);
        System.out.printf("FORCEFUL middle-third coverage: %.0f%%%n", middle * 100.0f);
        expect("FORCEFUL front-loads little and rips through the middle", middle > 0.6f);
        expect("FORCEFUL is symmetric about its midpoint",
                near(SwingDynamics.FORCEFUL.apply(0.5f), 0.5));

        // LUNGE is the opposite shape: it must still be gaining ground at full extension.
        var lungeEarly = SwingDynamics.LUNGE.apply(0.25f);
        expect("LUNGE starts slowly", lungeEarly < 0.15f);
        expect("LUNGE is still accelerating at the tip",
                SwingDynamics.LUNGE.apply(1.0f) - SwingDynamics.LUNGE.apply(0.9f)
                        > SwingDynamics.LUNGE.apply(0.2f) - SwingDynamics.LUNGE.apply(0.1f));
    }

    /// A player-sized victim box centred on the given point.
    private static AABB box(double x, double y, double z) {
        return new AABB(x - 0.3, y - 0.9, z - 0.3, x + 0.3, y + 0.9, z + 0.3);
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) < 0.02;
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private GeometryCheck() {
    }
}
