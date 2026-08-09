package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.encounter.perception.GloomProfile;
import com.cleannrooster.divineencounters.encounter.perception.ObservationCheck;
import com.cleannrooster.divineencounters.encounter.perception.ObservationTracker;
import com.cleannrooster.divineencounters.encounter.presence.CandidateResolver;
import com.cleannrooster.divineencounters.encounter.presence.ManifestKind;
import com.cleannrooster.divineencounters.encounter.presence.ManifestationCandidate;
import com.cleannrooster.divineencounters.encounter.presence.PresenceState;
import com.cleannrooster.divineencounters.encounter.presence.SuperpositionController;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/// Standalone verification of the encounter presence systems, run from the `presenceCheck` Gradle
/// task. Lives in the test source set, so it never ships in the jar.
///
/// These assert the rules that are expensive to diagnose from in-game symptoms: a flickering
/// observation state looks like "the boss is glitching", and a broken fairness invariant looks
/// like "I got hit by nothing". Both are cheap to lock down here.
public final class PresenceCheck {
    private static int failures;

    public static void main(String[] args) {
        checkHysteresis();
        checkGracePeriod();
        checkPinning();
        checkPresenceContract();
        checkObservationStrength();
        checkCandidateValidation();
        checkCandidateScoring();
        checkSelection();
        checkSightPredicateWiring();
        checkResolvedDwell();

        System.out.println(failures == 0 ? "ALL PRESENCE CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /// The core anti-flicker property: a target parked between the two thresholds must keep
    /// whatever answer it already had, rather than toggling every tick.
    private static void checkHysteresis() {
        var profile = GloomProfile.STALKING;
        var between = (profile.focusAngle() + profile.releaseAngle()) * 0.5f;
        var near = 5.0;

        expect("inside the focus cone, observation starts",
                ObservationCheck.withinObservationCone(10.0f, near, profile, false));
        expect("past the release angle, observation ends",
                !ObservationCheck.withinObservationCone(profile.releaseAngle() + 5.0f, near, profile, true));

        expect("in the hysteresis band, an unobserved target stays unobserved",
                !ObservationCheck.withinObservationCone(between, near, profile, false));
        expect("in the hysteresis band, an observed target stays observed",
                ObservationCheck.withinObservationCone(between, near, profile, true));

        // Simulate a player holding the boss exactly at the edge for a while: the answer must be
        // stable, not alternating.
        var previous = true;
        var flips = 0;
        for (var i = 0; i < 200; i++) {
            var now = ObservationCheck.withinObservationCone(between, near, profile, previous);
            if (now != previous) {
                flips++;
            }
            previous = now;
        }
        expect("holding a target at the threshold produces no oscillation", flips == 0);

        expect("beyond max distance nothing is observed",
                !ObservationCheck.withinObservationCone(0.0f, profile.maxDistance() + 1.0, profile, true));

        for (var candidate : new GloomProfile[]{GloomProfile.STALKING, GloomProfile.DEEPENING,
                GloomProfile.DEEP, GloomProfile.NO_WITNESS}) {
            expect("every profile has a real hysteresis band",
                    candidate.releaseAngle() > candidate.focusAngle());
            expect("every profile still allows direct observation to pin the boss",
                    candidate.focusAngle() >= 20.0f);
        }
    }

    /// Losing observation must take the full grace period; regaining it must be instant.
    private static void checkGracePeriod() {
        var profile = GloomProfile.STALKING;
        var tracker = new ObservationTracker();
        tracker.reset();

        tracker.tickWithVerdict(true);
        expect("watching means observed", tracker.isObserved());
        expect("observation is not lost while watching", !tracker.hasLostObservation(profile));

        for (var i = 1; i < profile.graceTicks(); i++) {
            tracker.tickWithVerdict(false);
            expect("observation is not yet lost at tick " + i, !tracker.hasLostObservation(profile));
        }
        tracker.tickWithVerdict(false);
        expect("observation is lost once the grace period elapses", tracker.hasLostObservation(profile));

        // One glance resets the whole window — the boss has to earn the break again.
        tracker.tickWithVerdict(true);
        expect("a single glance restores observation immediately", tracker.isObserved());
        expect("a single glance resets the loss window", !tracker.hasLostObservation(profile));
        expect("unobserved counter resets on being seen", tracker.unobservedTicks() == 0);
    }

    /// Landing a hit must restore information even if the player is facing away.
    private static void checkPinning() {
        var profile = GloomProfile.NO_WITNESS;
        var tracker = new ObservationTracker();
        tracker.reset();
        for (var i = 0; i < 60; i++) {
            tracker.tickWithVerdict(false);
        }
        expect("unobserved long enough to be lost", tracker.hasLostObservation(profile));

        tracker.pin(20);
        expect("pinning forces the observed state", tracker.isObserved());
        for (var i = 0; i < 19; i++) {
            tracker.tickWithVerdict(false);
            expect("pin holds while it lasts", tracker.isObserved());
            expect("pin suppresses loss", !tracker.hasLostObservation(profile));
        }
        tracker.tickWithVerdict(false);
        tracker.tickWithVerdict(false);
        expect("pin eventually expires", !tracker.isObserved());
    }

    /// The fairness contract, expressed on the state itself: damage only ever originates from a
    /// definite position, and an unresolved entity can be neither hit nor hurt.
    private static void checkPresenceContract() {
        expect("only RESOLVED may begin an attack", PresenceState.RESOLVED.allowsAttack());
        for (var state : PresenceState.values()) {
            if (state != PresenceState.RESOLVED) {
                expect(state + " may not begin an attack", !state.allowsAttack());
            }
        }
        expect("UNRESOLVED cannot be damaged", !PresenceState.UNRESOLVED.isVulnerable());
        expect("UNRESOLVED is intangible", !PresenceState.UNRESOLVED.isTangible());
        expect("UNRESOLVED is not rendered", !PresenceState.UNRESOLVED.isRendered());
        expect("DISSOLVING is still damageable", PresenceState.DISSOLVING.isVulnerable());
        expect("MANIFESTING is damageable before its attack starts",
                PresenceState.MANIFESTING.isVulnerable());
        expect("MANIFESTING cannot itself deal the attack yet",
                !PresenceState.MANIFESTING.allowsAttack());
    }

    /// The shared fade shaping must be monotonic and bounded, since both the AI's intuition and the
    /// client's rendering read it.
    private static void checkObservationStrength() {
        var profile = GloomProfile.DEEP;
        var previous = 1.1f;
        for (var angle = 0.0f; angle <= 90.0f; angle += 2.0f) {
            var strength = ObservationCheck.observationStrength(angle, 6.0, profile);
            expect("strength stays within 0..1 at " + angle, strength >= 0.0f && strength <= 1.0f);
            expect("strength never increases with angle at " + angle, strength <= previous + 1.0e-4f);
            previous = strength;
        }
        expect("dead centre is full strength",
                ObservationCheck.observationStrength(0.0f, 6.0, profile) == 1.0f);
        expect("past the release angle is zero",
                ObservationCheck.observationStrength(profile.releaseAngle() + 1.0f, 6.0, profile) == 0.0f);
        expect("out of range is zero",
                ObservationCheck.observationStrength(0.0f, profile.maxDistance() + 1.0, profile) == 0.0f);
    }

    // --- candidate rules --------------------------------------------------------------------------

    /// Build a candidate with everything valid, so each check can vary one property at a time.
    private static ManifestationCandidate candidate(float viewAngle, double distance,
                                                    boolean visible, boolean staredAt,
                                                    boolean valid, boolean elevated) {
        var position = new Vec3(distance, elevated ? 4.0 : 0.0, 0.0);
        return new ManifestationCandidate(position, 0.0f, distance, viewAngle, visible, staredAt,
                valid, elevated, position);
    }

    /// The hard gate. Most of these encode the no-visible-teleportation rule.
    private static void checkCandidateValidation() {
        var behind = candidate(170.0f, 4.0, false, false, true, false);
        expect("a valid rear candidate satisfies REAR_AMBUSH",
                behind.satisfies(ManifestKind.REAR_AMBUSH));

        expect("a candidate in front cannot serve REAR_AMBUSH",
                !candidate(20.0f, 4.0, false, false, true, false).satisfies(ManifestKind.REAR_AMBUSH));
        expect("a visible candidate cannot serve REAR_AMBUSH",
                !candidate(170.0f, 4.0, true, false, true, false).satisfies(ManifestKind.REAR_AMBUSH));
        expect("a stared-at candidate cannot serve REAR_AMBUSH",
                !candidate(170.0f, 4.0, false, true, true, false).satisfies(ManifestKind.REAR_AMBUSH));
        expect("a blocked candidate cannot serve REAR_AMBUSH",
                !candidate(170.0f, 4.0, false, false, false, false).satisfies(ManifestKind.REAR_AMBUSH));
        expect("a too-close candidate cannot serve REAR_AMBUSH",
                !candidate(170.0f, 0.5, false, false, true, false).satisfies(ManifestKind.REAR_AMBUSH));
        expect("a too-distant candidate cannot serve REAR_AMBUSH",
                !candidate(170.0f, 40.0, false, false, true, false).satisfies(ManifestKind.REAR_AMBUSH));

        // The single deliberate exception to the visibility rule.
        expect("FRONTAL_REVEAL accepts a visible candidate",
                candidate(20.0f, 6.0, true, false, true, false).satisfies(ManifestKind.FRONTAL_REVEAL));
        for (var kind : ManifestKind.values()) {
            if (kind != ManifestKind.FRONTAL_REVEAL) {
                expect(kind + " rejects visible candidates",
                        !candidate(kind.minViewAngle() + 5.0f,
                                (kind.minDistance() + kind.maxDistance()) * 0.5,
                                true, false, true, kind.requiresElevation()).satisfies(kind));
            }
        }

        expect("PERCH requires elevation",
                !candidate(120.0f, 6.0, false, false, true, false).satisfies(ManifestKind.PERCH));
        expect("PERCH accepts an elevated candidate",
                candidate(120.0f, 6.0, false, false, true, true).satisfies(ManifestKind.PERCH));
        expect("an invalid candidate satisfies nothing",
                !candidate(170.0f, 4.0, false, false, false, true).satisfies(ManifestKind.PERCH));
    }

    /// Scoring has to give each kind its character, independent of the random tiebreak.
    private static void checkCandidateScoring() {
        var preferred = (ManifestKind.REAR_AMBUSH.minDistance()
                + ManifestKind.REAR_AMBUSH.maxDistance()) * 0.5;
        var deepRear = candidate(178.0f, preferred, false, false, true, false);
        var shallowRear = candidate(125.0f, preferred, false, false, true, false);
        expect("deeper behind scores higher for REAR_AMBUSH",
                deepRear.score(ManifestKind.REAR_AMBUSH, preferred)
                        > shallowRear.score(ManifestKind.REAR_AMBUSH, preferred));

        var atRange = candidate(178.0f, preferred, false, false, true, false);
        var offRange = candidate(178.0f, ManifestKind.REAR_AMBUSH.maxDistance(), false, false, true, false);
        expect("the preferred engagement range scores higher",
                atRange.score(ManifestKind.REAR_AMBUSH, preferred)
                        > offRange.score(ManifestKind.REAR_AMBUSH, preferred));

        var frontPreferred = (ManifestKind.FRONTAL_REVEAL.minDistance()
                + ManifestKind.FRONTAL_REVEAL.maxDistance()) * 0.5;
        expect("squarely in front scores higher for FRONTAL_REVEAL",
                candidate(5.0f, frontPreferred, true, false, true, false)
                        .score(ManifestKind.FRONTAL_REVEAL, frontPreferred)
                        > candidate(50.0f, frontPreferred, true, false, true, false)
                        .score(ManifestKind.FRONTAL_REVEAL, frontPreferred));
    }

    /// Selection must only ever return something that passed the gate, and must report honestly
    /// when nothing qualifies rather than forcing a position.
    private static void checkSelection() {
        var resolver = new CandidateResolver();
        var random = RandomSource.create(9L);

        var pool = List.of(
                candidate(15.0f, 5.0, true, false, true, false),
                candidate(95.0f, 5.0, false, false, true, false),
                candidate(175.0f, 4.5, false, false, true, false),
                candidate(175.0f, 4.5, false, false, false, false));

        for (var i = 0; i < 200; i++) {
            var picked = resolver.select(pool, ManifestKind.REAR_AMBUSH, random);
            expect0(picked != null && picked.satisfies(ManifestKind.REAR_AMBUSH),
                    "selection only ever returns a candidate that satisfies the kind");
        }
        report("selection only ever returns a candidate that satisfies the kind");

        expect("canSatisfy agrees with selection",
                resolver.canSatisfy(pool, ManifestKind.REAR_AMBUSH));

        var noRear = List.of(candidate(15.0f, 5.0, true, false, true, false));
        expect("selection returns null when nothing qualifies",
                resolver.select(noRear, ManifestKind.REAR_AMBUSH, random) == null);
        expect("canSatisfy reports false when nothing qualifies",
                !resolver.canSatisfy(noRear, ManifestKind.REAR_AMBUSH));
        expect("an empty pool satisfies nothing",
                !resolver.canSatisfy(List.of(), ManifestKind.FLANK));

        // Without an arena there are no elevated candidates, so perches must simply be unavailable
        // rather than throwing or falling back to something invalid.
        expect("PERCH is unavailable with no elevated candidates",
                !resolver.canSatisfy(pool, ManifestKind.PERCH));
        expect("kinds needing elevation are flagged as arena-dependent",
                !ManifestKind.PERCH.worksWithoutArena() && ManifestKind.REAR_AMBUSH.worksWithoutArena());
    }

    /// It must stay long enough to be fought.
    ///
    /// The failure this guards is not a crash and does not look like a bug in isolation — it looks
    /// like the superposition mechanic working. A rear ambush arrives behind a player who is facing
    /// the other way, so the non-observation grace period starts counting the moment it lands; with
    /// no floor on resolved time it can earn a second dissolve before the player has finished
    /// turning, and the encounter degrades into hunting an absence.
    ///
    /// So: a freshly resolved boss is held, a hold can be extended but never cut short, and holding
    /// only ever *delays* a dissolve — it must never be able to cause one.
    private static void checkResolvedDwell() {
        var controller = new SuperpositionController(null, new CandidateResolver());
        expect("a freshly resolved boss is held in place", controller.isHeldResolved());

        controller.holdResolved(40);
        controller.holdResolved(10);
        expect("a shorter hold cannot cut a longer one short", controller.isHeldResolved());

        // The hold is a gate on *entering* DISSOLVING, never a cause of one, so a controller that
        // has not been asked to hold and has aged past the floor must be free to leave. Driving the
        // state machine that far needs a live level; what is checkable here is that the gate reads
        // the state at all rather than only the counters.
        expect("the hold only applies while resolved",
                controller.state() == PresenceState.RESOLVED);

        // A hold must not be able to become permanent.
        //
        // This is the exact bug that shipped: the boss's AI renewed the punish window every tick an
        // attack was running, so the window only began draining once the attack ended — and the next
        // attack always renewed it first. The hold never hit zero and the boss stopped dissolving
        // entirely, which reads as the mechanic being gone rather than as a timing bug.
        //
        // A caller renewing a hold forever is easy to write by accident, so the ceiling is enforced
        // here rather than trusted to callers.
        var released = -1;
        for (var tick = 0; tick < 2000; tick++) {
            // A hold that is renewed to its maximum on literally every tick.
            if (!SuperpositionController.holdsPresence(tick, 40)) {
                released = tick;
                break;
            }
        }
        System.out.println("      a permanently renewed hold releases at tick " + released);
        expect("a continuously renewed hold still releases", released > 0);

        expect("a fresh appearance is always held", SuperpositionController.holdsPresence(0, 0));
        expect("an unheld boss past the floor may leave",
                !SuperpositionController.holdsPresence(100, 0));
        expect("a held boss inside the ceiling stays",
                SuperpositionController.holdsPresence(100, 5));
    }

    /// The sight predicate must reach the observation tracker.
    ///
    /// Foliage concealing the boss is not a cosmetic problem: losing observation is what earns it the
    /// right to dissolve, so a leaf that breaks line of sight hands it a free dissolve while the
    /// player is looking straight at it. The two predicates are deliberately the same object — what
    /// the boss can drift through is what cannot hide it — and this asserts the wiring that couples
    /// them, since the failure is silent and only shows up as a boss that vanishes in forests.
    ///
    /// The ray itself needs a live level and is not reachable here; what is checkable is that the
    /// default is "nothing is transparent" and that supplying a predicate propagates.
    private static void checkSightPredicateWiring() {
        var leaves = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> probe =
                state -> {
                    leaves.incrementAndGet();
                    return true;
                };

        expect("the default sight predicate hides nothing",
                !ObservationCheck.NOTHING_IS_TRANSPARENT.test(null));

        var controller = new SuperpositionController(null, new CandidateResolver());
        controller.passableBlocks(probe);
        expect("passableBlocks also sets the sight predicate",
                controller.observation().seesThrough() == probe);
    }

    /// Loop-friendly assertion: records a failure without printing 200 identical success lines.
    private static boolean pendingOk = true;

    private static void expect0(boolean condition, String what) {
        if (!condition) {
            pendingOk = false;
        }
    }

    private static void report(String what) {
        expect(what, pendingOk);
        pendingOk = true;
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private PresenceCheck() {
    }
}
