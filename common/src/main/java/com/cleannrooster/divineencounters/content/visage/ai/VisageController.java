package com.cleannrooster.divineencounters.content.visage.ai;

import com.cleannrooster.divineencounters.combat.AttackPhase;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import com.cleannrooster.divineencounters.combat.TrackingMode;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.cleannrooster.divineencounters.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/// Server-authoritative movement and behaviour director for the Visage of War, ticked once per server
/// tick. Structurally this is the Sculken Raven's movement controller — one authoritative state at a
/// time, states that run to completion, a weighted director consulted only at action boundaries — retuned
/// for a humanoid duellist instead of a large circling flier.
///
/// What changed from the Raven, and why:
///
/// - The Raven alternates a grounded and an airborne posture. The Visage is always airborne, so there is
///   no takeoff/landing tree and no walk cycle to drive.
/// - The Raven picks a destination on a ring around its target and commits to flying there. The Visage
///   steers directly at her target during pursuit, and uses explicit {@link RepositionMove} arcs when she
///   wants a new angle — pressure or deliberate repositioning, never aimless orbiting.
/// - The Raven's actions are self-contained states with hand-written contact frames. The Visage's actions
///   are {@link DirectionalAttack} definitions run by the shared attack system, so this class only
///   decides *which* and *when*.
///
/// The intended rhythm is `pressure -> reposition -> attack -> reposition -> attack`: she is fast and
/// unpredictable between actions and deliberate during them. Movement speeds here are tuned high on
/// purpose; attack timings in {@link VisageAttacks} are tuned slow on purpose. Once an attack commits,
/// the attack definition — not this class — decides how much she is still allowed to turn.
public final class VisageController {
    // --- combat floor ------------------------------------------------------------------------------
    /// She never sinks below the player's own level. The target's Y is a dynamic floor, so on sloping or
    /// dropping terrain she stays on the fight plane instead of appearing to fall away toward the ground.
    private static final double FLOOR_MARGIN = 0.2;
    /// How hard she is pushed back up when she is under the floor, and the cap on that correction.
    private static final double FLOOR_GAIN = 0.5, FLOOR_RECOVER_SPEED = 0.5;

    // --- hover / pursuit ---------------------------------------------------------------------------
    /// Preferred height above whatever is beneath her. Low enough to read as a duellist, high enough
    /// that her feet clear terrain.
    ///
    /// The band is deliberately narrow. A wide one produced a slow half-block rise and fall that read
    /// as buoyancy — the thing bobbing was being *carried* by the air rather than holding itself
    /// against it. Malice is allowed to look suspended; War should look braced. What remains is a
    /// tenth of a block, present only so she is not mathematically frozen.
    private static final double HOVER_MIN = 0.95, HOVER_MAX = 1.05;
    private static final double HOVER_GAIN = 0.5, HOVER_RISE_CAP = 0.62, HOVER_FALL_CAP = 0.34;
    private static final double HOVER_DAMP = 0.45;
    /// The band she wants to fight in.
    private static final double PREFERRED_MIN = 4.0, PREFERRED_MAX = 5.0;
    /// Beyond this she accelerates hard; inside `SLIP_DIST` she stops closing and starts sliding past.
    private static final double CLOSE_FAST_DIST = 7.0, SLIP_DIST = 2.3;
    /// Far enough that she re-establishes pressure with a charge instead of a long, dull approach.
    private static final double CHARGE_TRIGGER_DIST = 9.0;
    private static final double PURSUE_SPEED = 0.52, CLOSE_SPEED = 0.78, DRIFT_SPEED = 0.18;
    /// Sideways component applied when the player body-blocks her, so she glides around rather than
    /// grinding to a halt against them.
    private static final double SLIP_SPEED = 0.32;
    /// How quickly steering velocity converges on the desired velocity.
    ///
    /// These are the *launch* rates — how fast she gains speed. Deceleration is a separate, much
    /// higher rate; see {@link #driveTo}.
    private static final double PURSUIT_ACCEL = 0.4, REPOSITION_ACCEL = 0.55;

    // --- momentum ----------------------------------------------------------------------------------
    /// Losing speed is roughly three times faster than gaining it.
    ///
    /// This asymmetry is the whole "inertia obeys her" read. A symmetric blend — one rate for both —
    /// makes anything heavy feel *floaty*, because the same lag that reads as mass while accelerating
    /// reads as sliding while stopping. Splitting them lets her keep visible build-up into a burst
    /// while still arresting like something that simply decided to stop.
    private static final double ARREST_RATE = 0.72;
    /// Below this speed, an arresting body is set to its target outright instead of approaching it
    /// asymptotically. An exponential blend never actually reaches zero, and that infinitely long
    /// tail is precisely the mushy coast this pass exists to remove.
    private static final double ARREST_SNAP = 0.045;
    /// Minimum speed change per tick when launching from near-rest, in blocks/tick. A proportional
    /// blend is slowest exactly when it should be most decisive — at the start — so this floor gives
    /// the first few ticks of a burst real authority.
    private static final double LAUNCH_FLOOR = 0.055;

    // --- turning -----------------------------------------------------------------------------------
    private static final float PURSUIT_TURN = 14.0f;
    private static final float REPOSITION_TURN = 16.0f;
    private static final float REACQUIRE_TURN = 5.0f;
    /// A successful flank should still buy time, so reacquisition stays a visible pause — just a shorter
    /// one than before.
    private static final int REACQUIRE_TICKS = 16, REACQUIRE_TICKS_PHASE_TWO = 10;

    // --- pacing ------------------------------------------------------------------------------------
    /// Idle gap between actions. She never simply waits — pursuit continues through it.
    private static final int ATTACK_GAP = 12, ATTACK_GAP_PHASE_TWO = 6;
    /// Ticks shaved off the tail of every recovery once Divine Pursuit is active.
    private static final int RECOVERY_TRIM_PHASE_TWO = 4;
    /// Frontal attacks are refused outside this half-angle; she turns or repositions instead of swinging
    /// at air.
    private static final float FRONTAL_CONE = 55.0f;
    /// How strongly the last two attacks are penalised, so she doesn't loop one move.
    private static final double REPEAT_PENALTY = 0.25, RECENT_PENALTY = 0.55;
    private static final int HISTORY_SIZE = 3;

    // --- repositioning -----------------------------------------------------------------------------
    /// Minimum gap between repositions, so she can't chain semicircles forever without committing.
    private static final int REPOSITION_COOLDOWN = 40, REPOSITION_COOLDOWN_PHASE_TWO = 26;
    /// Below this, an arc would be a shove rather than a reposition; above it she'd rather just close.
    private static final double REPOSITION_MIN_DIST = 2.6, REPOSITION_MAX_DIST = 9.0;
    /// Ticks she'll tolerate sitting in the player's front arc before wanting a new angle.
    private static final int STALE_ANGLE_TICKS = 45;
    /// Gain on the arc's position-seeking, and the cap on the resulting speed. Repositions are the
    /// fastest thing she does.
    private static final double REPOSITION_GAIN = 0.6, REPOSITION_SPEED_CAP = 1.05;

    // --- charge ------------------------------------------------------------------------------------
    private static final double CHARGE_SPEED = 0.9;
    private static final double CHARGE_MIN_DISTANCE = 8.0, CHARGE_MAX_DISTANCE = 12.0;

    // --- Judgment Fall -----------------------------------------------------------------------------
    private static final double DIVE_RISE_HEIGHT = 4.5, DIVE_RISE_SPEED = 0.7;
    private static final int DIVE_RISE_MAX_TICKS = 26, DIVE_TRACK_TICKS = 14, DIVE_MAX_TICKS = 40;
    private static final double DIVE_SPEED = 1.25;

    // --- Divine Pursuit ----------------------------------------------------------------------------
    public static final float DIVINE_PURSUIT_THRESHOLD = 0.6f;
    private static final double TRANSITION_RISE_HEIGHT = 8.0, TRANSITION_RISE_SPEED = 0.7;
    private static final int TRANSITION_RISE_TICKS = 30, TRANSITION_PASSES = 3;
    private static final int TRANSITION_SETTLE_TICKS = 18;

    private final VisageOfWarEntity visage;
    private final RandomSource random;

    private int attackGap;
    private int repositionGap;
    private final Map<ResourceLocation, Integer> cooldowns = new HashMap<>();
    private final Deque<DirectionalAttack> history = new ArrayDeque<>();
    private @Nullable String lastRepositionId;
    private boolean lastActionWasReposition;
    /// How long she has sat inside the player's front arc — the main trigger for wanting a new angle.
    private int ticksInPlayerFront;

    /// Velocity the running attack wants to contribute this tick, collected from the attack runner and
    /// folded into the steering rather than fighting it.
    private Vec3 attackMotion = Vec3.ZERO;
    /// Smoothed steering velocity, so speed changes read as acceleration rather than teleport-like snaps.
    private Vec3 steering = Vec3.ZERO;

    // Reposition state.
    private @Nullable RepositionMove move;
    private int moveSide = 1;
    private double moveStartAngle;
    private double moveStartY;
    private @Nullable DirectionalAttack moveFollowUp;
    /// Consecutive ticks she has failed to make progress along an arc — terrain in the way, most likely.
    private int moveStalledTicks;
    private Vec3 movePreviousPosition = Vec3.ZERO;

    // Charge state.
    private Vec3 chargeDirection = Vec3.ZERO;
    private Vec3 chargeStart = Vec3.ZERO;
    private double chargeDistance;

    // Judgment Fall state.
    private Vec3 diveTarget = Vec3.ZERO;

    // Divine Pursuit transition state.
    private int transitionPass;
    private int transitionStage;

    private @Nullable DirectionalAttack chainNext;

    public VisageController(VisageOfWarEntity visage) {
        this.visage = visage;
        this.random = visage.getRandom();
    }

    /// Called by the attack runner's motion sink. Attacks contribute velocity; the controller decides how
    /// it combines with hovering.
    public void addAttackMotion(Vec3 motion) {
        this.attackMotion = this.attackMotion.add(motion);
    }

    // --- main tick ---------------------------------------------------------------------------------

    public void serverTick() {
        this.attackMotion = Vec3.ZERO;
        tickCooldowns();

        var runner = this.visage.attacks();
        runner.tick();

        var target = validTarget();
        trackPlayerFrontArc(target);

        // Vertical aim is updated once here, before any state can commit an attack.
        //
        // Doing it inside faceTarget would be enough most of the time and wrong occasionally: an
        // attack captures its frame the instant it starts, and not every path calls faceTarget before
        // starting one. Updating it up front means the aim is current at every commit point rather
        // than at most of them.
        this.visage.aimAt(target);

        // The phase change pre-empts everything except itself.
        if (this.visage.shouldEnterDivinePursuit()
                && this.visage.getVisageState() != VisageState.DIVINE_PURSUIT_TRANSITION) {
            beginDivinePursuitTransition();
        }

        switch (this.visage.getVisageState()) {
            case PURSUIT -> tickPursuit(target);
            case REPOSITION, AERIAL_REPOSITION -> tickReposition(target);
            case ATTACK_WINDUP, ATTACK_ACTIVE, ATTACK_RECOVERY -> tickAttack(target);
            case CHARGE -> tickCharge(target);
            case AERIAL_RISE -> tickAerialRise(target);
            case AERIAL_DIVE -> tickAerialDive();
            case REACQUIRE -> tickReacquire(target);
            case DIVINE_PURSUIT_TRANSITION -> tickTransition(target);
        }

        decayBank();

        if (this.visage.tickCount % 24 == 0 && this.visage.getVisageState() != VisageState.PURSUIT) {
            this.visage.level().playSound(null, this.visage.getX(), this.visage.getY(), this.visage.getZ(),
                    ModSounds.VISAGE_WINGS.get(), SoundSource.HOSTILE, 0.6f, 1.0f);
        }
    }

    private @Nullable LivingEntity validTarget() {
        var target = this.visage.getTarget();
        return target != null && target.isAlive() ? target : null;
    }

    private void tickCooldowns() {
        this.cooldowns.replaceAll((id, value) -> Math.max(0, value - 1));
        if (this.attackGap > 0) {
            this.attackGap--;
        }
        if (this.repositionGap > 0) {
            this.repositionGap--;
        }
    }

    /// Counts how long she has been sitting inside the player's forward arc. Staying parked where the
    /// player is already looking is exactly the situation a reposition exists to break.
    private void trackPlayerFrontArc(@Nullable LivingEntity target) {
        if (target == null) {
            this.ticksInPlayerFront = 0;
            return;
        }
        var toVisage = horizontal(this.visage.position().subtract(target.position()));
        if (toVisage.lengthSqr() < 1.0e-4) {
            this.ticksInPlayerFront++;
            return;
        }
        var look = horizontal(target.getLookAngle());
        var facing = look.lengthSqr() < 1.0e-4 ? toVisage : look.normalize();
        if (facing.dot(toVisage.normalize()) > 0.55) {
            this.ticksInPlayerFront++;
        } else {
            this.ticksInPlayerFront = 0;
        }
    }

    // --- pursuit -----------------------------------------------------------------------------------

    private void tickPursuit(@Nullable LivingEntity target) {
        if (target == null) {
            hoverIdle();
            return;
        }
        faceTarget(target, PURSUIT_TURN * this.visage.aggressionScale());
        steerTowardTarget(target);

        if (this.attackGap <= 0 && !this.visage.attacks().isBusy()) {
            chooseAction(target);
        }
    }

    /// Pick the next thing she does: reposition for a new angle, or attack from where she stands.
    ///
    /// A reposition is preferred when the current angle is stale or the geometry is poor, and refused
    /// twice in a row — the point of moving is to create an attack, not to circle.
    private void chooseAction(LivingEntity target) {
        if (this.repositionGap <= 0 && !this.lastActionWasReposition) {
            var reposition = chooseReposition(target);
            if (reposition != null) {
                beginReposition(reposition, target);
                return;
            }
        }
        // Cleared here rather than in launch(): a decision cycle has now passed since the arc ended, so
        // the next one may reposition again even if no attack was legal this time. Clearing it only on a
        // successful launch would strand her — a reposition that ends at a bad angle produces no attack,
        // and she would never be allowed to move for a better one.
        this.lastActionWasReposition = false;

        var attack = chooseAttack(target);
        if (attack != null) {
            launch(attack, target);
        }
    }

    /// The pressure loop: close hard when far, keep drifting forward at the preferred range, and slide
    /// around the player rather than into them at contact range. She never stops moving.
    private void steerTowardTarget(LivingEntity target) {
        var toTarget = horizontal(target.position().subtract(this.visage.position()));
        var distance = toTarget.length();
        var forward = distance < 1.0e-4 ? facing() : toTarget.normalize();

        double speed;
        var lateral = Vec3.ZERO;
        if (distance > CLOSE_FAST_DIST) {
            speed = CLOSE_SPEED;
        } else if (distance > PREFERRED_MAX) {
            speed = PURSUE_SPEED;
        } else if (distance > PREFERRED_MIN) {
            // Inside her preferred band she keeps creeping in. Standing off and waiting out a cooldown is
            // not an option she offers the player.
            speed = DRIFT_SPEED;
        } else if (distance > SLIP_DIST) {
            // Closer than she wants but not yet touching: ease off without ever fully stopping.
            speed = DRIFT_SPEED * 0.6;
        } else {
            // Contact range: stop pushing into the player's hitbox and glide around them instead. This is
            // what keeps a body-blocking player from pinning her in place.
            speed = 0.02;
            var side = new Vec3(-forward.z, 0.0, forward.x);
            lateral = side.scale(this.visage.slipDirection() * SLIP_SPEED);
        }
        speed *= this.visage.aggressionScale();

        driveTo(forward.scale(speed).add(lateral), PURSUIT_ACCEL);
        applyMotion(this.steering, hoverRise(target));
    }

    private void hoverIdle() {
        // Composure: she arrests to a genuine standstill rather than drifting toward one.
        driveTo(Vec3.ZERO, PURSUIT_ACCEL);
        applyMotion(this.steering, hoverRise(null));
    }

    /// Drive the steering velocity toward `desired` with force.
    ///
    /// Three departures from a plain exponential blend, each targeting one way a heavy thing can
    /// accidentally read as light:
    ///
    /// 1. **Asymmetry.** Gaining speed uses `launchRate`; losing it uses the much higher
    ///    {@link #ARREST_RATE}. She accelerates with visible build-up and decelerates like a decision.
    /// 2. **A launch floor.** A proportional blend moves slowest when the gap is smallest, which
    ///    makes the *start* of a burst its weakest moment — exactly backwards. {@link #LAUNCH_FLOOR}
    ///    guarantees a minimum step so she breaks away rather than oozing into motion.
    /// 3. **A snap threshold.** An exponential approach never reaches its target, so a stop leaves a
    ///    long tail of imperceptible drift. Under {@link #ARREST_SNAP} she is simply set to the
    ///    target — the "impossible control" is literally this: the tail is deleted rather than
    ///    shortened.
    ///
    /// Readability is unaffected. Nothing here touches the windup or telegraph of anything; it only
    /// changes how the release and the stop feel once commitment has already been signalled.
    private void driveTo(Vec3 desired, double launchRate) {
        var delta = desired.subtract(this.steering);
        var gap = delta.length();
        if (gap < 1.0e-6) {
            this.steering = desired;
            return;
        }

        var slowing = desired.lengthSqr() < this.steering.lengthSqr();
        if (slowing) {
            if (gap < ARREST_SNAP) {
                // Hard, clean arrest. No settling.
                this.steering = desired;
                return;
            }
            this.steering = this.steering.add(delta.scale(ARREST_RATE));
            return;
        }

        var step = Math.max(gap * launchRate, Math.min(gap, LAUNCH_FLOOR));
        this.steering = this.steering.add(delta.scale(step / gap));
    }

    // --- vertical ----------------------------------------------------------------------------------

    /// Height she wants to be at: her hover offset above the terrain, but never below the player's own
    /// level. Treating the target's Y as a floor is what stops her drifting down a slope or sinking
    /// toward the ground while the player stands on higher footing.
    private double desiredHoverY(@Nullable LivingEntity target) {
        var groundY = groundHeight(this.visage.getX(), this.visage.getZ());
        var bob = Math.sin(this.visage.tickCount * 0.06) * 0.5 + 0.5;
        var desired = groundY + Mth.lerp(bob, HOVER_MIN, HOVER_MAX);
        var floor = combatFloor(target);
        return Double.isNaN(floor) ? desired : Math.max(desired, floor);
    }

    /// The dynamic fight floor: the target's current Y, or NaN when there is no floor to respect (no
    /// target, or a state that has explicitly opted out, like the Judgment Fall dive).
    private double combatFloor(@Nullable LivingEntity target) {
        if (target == null || !this.visage.getVisageState().respectsCombatFloor()) {
            return Double.NaN;
        }
        return target.getY() + FLOOR_MARGIN;
    }

    /// Vertical velocity holding her at the desired height, damped so it reads as floating rather than
    /// as a servo. The floor is enforced as a velocity constraint — she is never repositioned outright,
    /// she just stops being allowed to descend and is nudged back up.
    private double hoverRise(@Nullable LivingEntity target) {
        var desiredY = desiredHoverY(target);
        var error = desiredY - this.visage.getY();
        var rise = Mth.clamp(error * HOVER_GAIN, -HOVER_FALL_CAP, HOVER_RISE_CAP);
        var smoothed = this.visage.getDeltaMovement().y * HOVER_DAMP + rise * (1.0 - HOVER_DAMP);

        var floor = combatFloor(target);
        if (!Double.isNaN(floor) && this.visage.getY() < floor) {
            var deficit = floor - this.visage.getY();
            smoothed = Math.max(smoothed, Math.min(FLOOR_RECOVER_SPEED, deficit * FLOOR_GAIN + 0.03));
        }
        return smoothed;
    }

    /// Combine steering with whatever the running attack wants to contribute.
    private void applyMotion(Vec3 horizontal, double vertical) {
        var combined = horizontal.add(this.attackMotion);
        this.visage.setDeltaMovement(combined.x, vertical + this.attackMotion.y, combined.z);
    }

    // --- attack states -----------------------------------------------------------------------------

    private void tickAttack(@Nullable LivingEntity target) {
        var runner = this.visage.attacks();
        var execution = runner.current();
        if (execution == null) {
            finishAttack(target);
            return;
        }

        // Mirror the attack's own phase into the synced state, so animation and behaviour agree without
        // this class tracking timings the attack system already owns.
        this.visage.setVisageState(switch (execution.phase()) {
            case WINDUP -> VisageState.ATTACK_WINDUP;
            case ACTIVE -> VisageState.ATTACK_ACTIVE;
            case RECOVERY, FINISHED -> VisageState.ATTACK_RECOVERY;
        });

        // Turning is capped by the attack, not by the AI. Faster movement must not buy her better
        // tracking — that contrast between mobile and committed is the whole read of the fight.
        if (target != null) {
            var tracking = runner.tracking();
            if (tracking != TrackingMode.LOCKED) {
                faceTarget(target, tracking.degreesPerTick());
            }
        }

        // Divine Pursuit clips the tail off recoveries rather than speeding up any animation.
        if (this.visage.isDivinePursuit()
                && execution.phase() == AttackPhase.RECOVERY
                && execution.phaseTick() >= Math.max(0,
                execution.definition().recoveryTicks() - RECOVERY_TRIM_PHASE_TWO)) {
            runner.cancel();
        }

        // Attacks own her horizontal movement; the hover still holds her on the fight plane. She
        // arrests into the swing rather than decaying through it, so the attack's own advance is the
        // only motion the player reads during it.
        driveTo(Vec3.ZERO, PURSUIT_ACCEL);
        applyMotion(this.steering, hoverRise(target));
    }

    private void finishAttack(@Nullable LivingEntity target) {
        var chain = this.chainNext;
        this.chainNext = null;
        if (chain != null && target != null && this.visage.isDivinePursuit()) {
            launch(chain, target);
            return;
        }
        this.attackGap = attackGap();
        this.visage.setVisageState(VisageState.PURSUIT);
    }

    private int attackGap() {
        return this.visage.isDivinePursuit() ? ATTACK_GAP_PHASE_TWO : ATTACK_GAP;
    }

    // --- repositioning -----------------------------------------------------------------------------

    /// Weighted choice over the reposition moveset. Distance gates it, the stale-angle counter and a
    /// poor attack angle push toward it, and recency keeps her from repeating the same arc.
    private @Nullable RepositionMove chooseReposition(LivingEntity target) {
        var distance = horizontal(target.position().subtract(this.visage.position())).length();
        if (distance < REPOSITION_MIN_DIST || distance > REPOSITION_MAX_DIST) {
            return null;
        }
        var stale = this.ticksInPlayerFront >= STALE_ANGLE_TICKS;
        var offAngle = angleToTarget(target) > FRONTAL_CONE;

        var weights = new LinkedHashMap<RepositionMove, Double>();
        // The cheap, frequent option — always in the running.
        weights.put(RepositionMove.SIDE_STEP, stale ? 4.0 : 2.2);
        weights.put(RepositionMove.FLANKING_ARC, stale ? 3.0 : 1.6);
        if (distance > 3.5) {
            weights.put(RepositionMove.REAR_PASS, stale ? 2.4 : 1.2);
            weights.put(RepositionMove.REAR_REPOSITION, stale ? 2.0 : 0.9);
        }
        // Going over the top only pays off with room to climb and a heavy follow-up available.
        if (distance > 4.0 && available(VisageAttacks.JUDGMENT_FALL_IMPACT)) {
            weights.put(RepositionMove.OVERHEAD_ARC, 1.4);
        }
        // Facing the wrong way is best fixed by moving, not by standing still and turning.
        if (offAngle) {
            weights.merge(RepositionMove.SIDE_STEP, 2.0, Double::sum);
        }
        if (this.lastRepositionId != null) {
            weights.replaceAll((candidate, weight) ->
                    candidate.id().equals(this.lastRepositionId) ? weight * 0.3 : weight);
        }

        // She does not reposition every time she could — most cycles should still be a plain attack.
        var appetite = stale ? 0.85 : (this.visage.isDivinePursuit() ? 0.5 : 0.38);
        if (this.random.nextFloat() > appetite) {
            return null;
        }
        return pickWeighted(weights);
    }

    private void beginReposition(RepositionMove move, LivingEntity target) {
        this.move = move;
        this.lastRepositionId = move.id();
        this.lastActionWasReposition = true;
        this.repositionGap = this.visage.isDivinePursuit()
                ? REPOSITION_COOLDOWN_PHASE_TWO : REPOSITION_COOLDOWN;

        // Arc toward the side she is already offset to, so the curve reads as continuing her motion
        // rather than reversing it.
        this.moveSide = preferredArcSide(target);
        var offset = horizontal(this.visage.position().subtract(target.position()));
        this.moveStartAngle = offset.lengthSqr() < 1.0e-4
                ? Math.atan2(-facing().z, -facing().x)
                : Math.atan2(offset.z, offset.x);
        this.moveStartY = this.visage.getY();
        this.moveFollowUp = chooseFollowUp(move, target);
        this.moveStalledTicks = 0;
        this.movePreviousPosition = this.visage.position();
        // She has just changed the angle, so the stale-angle timer starts again from here.
        this.ticksInPlayerFront = 0;

        this.visage.setVisageState(move.isAerial() ? VisageState.AERIAL_REPOSITION : VisageState.REPOSITION);
        this.visage.resetStateTimer();
        this.visage.level().playSound(null, this.visage.getX(), this.visage.getY(), this.visage.getZ(),
                ModSounds.VISAGE_WINGS.get(), SoundSource.HOSTILE, 0.9f, 1.15f);
    }

    /// Prefer the side she is already drifting toward; fall back to a coin flip. Keeps arcs from
    /// reversing direction mid-fight for no visible reason.
    private int preferredArcSide(LivingEntity target) {
        var toTarget = horizontal(target.position().subtract(this.visage.position()));
        if (toTarget.lengthSqr() < 1.0e-4 || this.steering.lengthSqr() < 1.0e-4) {
            return this.random.nextBoolean() ? 1 : -1;
        }
        var forward = toTarget.normalize();
        var side = new Vec3(-forward.z, 0.0, forward.x);
        var drift = this.steering.dot(side);
        if (Math.abs(drift) < 0.02) {
            return this.random.nextBoolean() ? 1 : -1;
        }
        return drift > 0 ? 1 : -1;
    }

    /// Fly the arc. The destination is recomputed from the target's live position every tick, so a player
    /// who runs is still orbited rather than left behind, and the path stays a visible curve.
    private void tickReposition(@Nullable LivingEntity target) {
        var move = this.move;
        if (move == null || target == null) {
            endReposition(target);
            return;
        }
        var elapsed = this.visage.stateTimer();
        var raw = Mth.clamp((float) elapsed / move.durationTicks(), 0.0f, 1.0f);
        // Ease in and out so she accelerates into the arc and settles out of it.
        var progress = raw * raw * (3.0f - 2.0f * raw);

        var angle = this.moveStartAngle
                + Math.toRadians(move.sweepDegrees() * this.moveSide) * progress;
        var radius = Mth.lerp(progress, move.radiusStart(), move.radiusEnd());
        var destination = new Vec3(
                target.getX() + Math.cos(angle) * radius,
                0.0,
                target.getZ() + Math.sin(angle) * radius);

        var toDestination = horizontal(destination.subtract(this.visage.position()));
        var desired = clampLength(toDestination.scale(REPOSITION_GAIN), REPOSITION_SPEED_CAP);
        driveTo(desired, REPOSITION_ACCEL);

        // Vertical: her normal hover, lifted by however much the arc climbs.
        var lift = move.riseHeight() * progress;
        var vertical = repositionRise(target, lift);
        applyMotion(this.steering, vertical);

        // She keeps her eyes on the player through the whole arc — that is what makes a flank read as
        // circling rather than fleeing.
        faceTarget(target, REPOSITION_TURN);
        this.visage.setPitchOverride(move.pitchDegrees() * progress);
        // Bank into the turn, then roll back level as the arc finishes.
        var bankCurve = Mth.sin(progress * Mth.PI);
        this.visage.setBank(move.bankDegrees() * this.moveSide * bankCurve);

        // Terrain can block an arc. Rather than grinding against a wall for the full duration, give up
        // and let her attack from where she is.
        var travelled = this.visage.position().subtract(this.movePreviousPosition).lengthSqr();
        this.movePreviousPosition = this.visage.position();
        this.moveStalledTicks = travelled < 0.0016 ? this.moveStalledTicks + 1 : 0;

        if (elapsed >= move.durationTicks() || this.moveStalledTicks >= 5) {
            endReposition(target);
        }
    }

    /// Vertical control during an arc: the usual floor-respecting hover, offset upward by the arc's climb
    /// so an overhead sweep genuinely rises over the player.
    private double repositionRise(LivingEntity target, double lift) {
        if (lift <= 0.0) {
            return hoverRise(target);
        }
        var desiredY = Math.max(desiredHoverY(target), this.moveStartY + lift);
        var error = desiredY - this.visage.getY();
        var rise = Mth.clamp(error * HOVER_GAIN, -HOVER_FALL_CAP, HOVER_RISE_CAP * 1.4);
        return this.visage.getDeltaMovement().y * HOVER_DAMP + rise * (1.0 - HOVER_DAMP);
    }

    /// Repositions exist to create an attack, so she normally swings the moment one lands.
    private void endReposition(@Nullable LivingEntity target) {
        var followUp = this.moveFollowUp;
        this.move = null;
        this.moveFollowUp = null;
        this.visage.setPitchOverride(0.0f);

        if (followUp != null && target != null) {
            launch(followUp, target);
            return;
        }
        this.attackGap = 0; // she has already spent the time moving; attack as soon as the angle allows
        this.visage.setVisageState(VisageState.PURSUIT);
    }

    /// The attack a reposition is setting up. Chosen up front so the arc can be selected for the attack
    /// rather than the other way round.
    private @Nullable DirectionalAttack chooseFollowUp(RepositionMove move, LivingEntity target) {
        return switch (move.followUp()) {
            case FAST_STRIKE -> firstAvailable(VisageAttacks.LANCE_THRUST, VisageAttacks.WING_SWEEP,
                    VisageAttacks.DESCENDING_CUT);
            case HEAVY_STRIKE -> firstAvailable(VisageAttacks.SUNDERING_SWEEP, VisageAttacks.HEAVENS_DIVIDE,
                    VisageAttacks.DESCENDING_CUT, VisageAttacks.WING_SWEEP);
            case AERIAL_STRIKE -> firstAvailable(VisageAttacks.JUDGMENT_FALL_IMPACT,
                    VisageAttacks.DESCENDING_CUT);
            case ANY -> chooseAttack(target);
        };
    }

    private @Nullable DirectionalAttack firstAvailable(DirectionalAttack... candidates) {
        for (var candidate : candidates) {
            if (available(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /// Roll levels out on its own whenever nothing is actively banking her. States that set their own
    /// roll are skipped, or the decay would fight them for the same value every tick.
    private void decayBank() {
        var state = this.visage.getVisageState();
        if (state.isRepositioning() || state == VisageState.DIVINE_PURSUIT_TRANSITION) {
            return;
        }
        this.visage.setBank(this.visage.getBank() * 0.82f);
    }

    // --- charge ------------------------------------------------------------------------------------

    private void beginCharge(LivingEntity target) {
        var toTarget = horizontal(target.position().subtract(this.visage.position()));
        this.chargeDirection = toTarget.lengthSqr() < 1.0e-4 ? facing() : toTarget.normalize();
        this.chargeStart = this.visage.position();
        this.chargeDistance = Mth.clamp(toTarget.length() + 3.0, CHARGE_MIN_DISTANCE, CHARGE_MAX_DISTANCE);
        // Aim at where she is going, then stop steering — she passes *through* the player's position.
        this.visage.setYRot((float) (Mth.atan2(this.chargeDirection.z, this.chargeDirection.x)
                * (180.0 / Math.PI)) - 90.0f);
        this.visage.setYBodyRot(this.visage.getYRot());
        this.visage.setVisageState(VisageState.CHARGE);
    }

    private void tickCharge(@Nullable LivingEntity target) {
        var runner = this.visage.attacks();
        var execution = runner.current();
        if (execution == null) {
            beginReacquire();
            return;
        }
        switch (execution.phase()) {
            // Short, readable preparation: she hangs still with her heading already committed.
            //
            // "Hangs still" is now literal. The old 0.7 decay left her drifting through the entire
            // wind-up, which blunted the release — the burst had to overcome her own residual motion
            // before it read as a burst. Arresting to a dead stop first makes the launch land against
            // stillness, and stillness is what makes it look violent.
            case WINDUP -> {
                if (target != null) {
                    faceTarget(target, TrackingMode.MINIMAL.degreesPerTick());
                }
                driveTo(Vec3.ZERO, PURSUIT_ACCEL);
                applyMotion(this.steering, hoverRise(target));
            }
            // The pass itself. No correction — dodging it works.
            case ACTIVE -> {
                var travelled = horizontal(this.visage.position().subtract(this.chargeStart)).length();
                if (travelled >= this.chargeDistance) {
                    runner.cancel();
                    beginReacquire();
                    return;
                }
                // Full speed, immediately, held for the whole pass. No ramp: the charge is the one
                // place she should look like she *became* momentum rather than acquired it.
                this.steering = this.chargeDirection.scale(CHARGE_SPEED * this.visage.aggressionScale());
                applyMotion(this.steering, hoverRise(target));
            }
            // And then it is simply over. A 0.7 decay from charge speed leaves her coasting for the
            // better part of a second, which is the single most floaty thing she used to do; the
            // arrest curve stops her in a handful of ticks and then exactly stops.
            case RECOVERY, FINISHED -> {
                driveTo(Vec3.ZERO, PURSUIT_ACCEL);
                applyMotion(this.steering, hoverRise(target));
            }
        }
    }

    // --- Judgment Fall -----------------------------------------------------------------------------

    private void beginJudgmentFall(LivingEntity target) {
        this.diveTarget = target.position();
        this.visage.setVisageState(VisageState.AERIAL_RISE);
        this.visage.resetStateTimer();
        this.visage.level().playSound(null, this.visage.getX(), this.visage.getY(), this.visage.getZ(),
                ModSounds.VISAGE_ASCEND.get(), SoundSource.HOSTILE, 1.2f, 1.0f);
    }

    /// She climbs while still following her target, then stops tracking. The lock is the telegraph: the
    /// moment she stops turning is the moment the landing spot is fixed.
    private void tickAerialRise(@Nullable LivingEntity target) {
        var elapsed = this.visage.stateTimer();
        if (target != null && elapsed < DIVE_TRACK_TICKS) {
            faceTarget(target, PURSUIT_TURN);
            this.diveTarget = target.position();
        }
        var floor = target != null ? target.getY() : groundHeight(this.visage.getX(), this.visage.getZ());
        var reached = this.visage.getY() >= floor + DIVE_RISE_HEIGHT;
        this.steering = this.steering.scale(0.75);
        this.visage.setDeltaMovement(this.steering.x, reached ? 0.0 : DIVE_RISE_SPEED, this.steering.z);
        this.visage.setPitchOverride(-25.0f);

        if ((reached && elapsed >= DIVE_TRACK_TICKS) || elapsed >= DIVE_RISE_MAX_TICKS) {
            this.visage.setVisageState(VisageState.AERIAL_DIVE);
            this.visage.resetStateTimer();
        }
    }

    private void tickAerialDive() {
        var toTarget = this.diveTarget.subtract(this.visage.position());
        var direction = toTarget.lengthSqr() < 1.0e-4 ? new Vec3(0.0, -1.0, 0.0) : toTarget.normalize();
        this.visage.setDeltaMovement(direction.scale(DIVE_SPEED));
        this.steering = Vec3.ZERO;
        this.visage.setPitchOverride(55.0f);

        var groundY = groundHeight(this.visage.getX(), this.visage.getZ());
        var landed = this.visage.getY() <= Math.max(this.diveTarget.y, groundY) + 0.6;
        if (landed || this.visage.stateTimer() >= DIVE_MAX_TICKS) {
            this.visage.attacks().start(VisageAttacks.JUDGMENT_FALL_IMPACT);
            this.visage.setPitchOverride(0.0f);
            beginReacquire();
        }
    }

    // --- reacquisition -----------------------------------------------------------------------------

    /// The deliberate opening after a pass or a dive. She turns slowly here, which is what makes getting
    /// behind her worth doing — faster movement elsewhere does not buy her a faster turn.
    private void beginReacquire() {
        this.visage.setVisageState(VisageState.REACQUIRE);
        this.visage.resetStateTimer();
        this.attackGap = attackGap();
    }

    private void tickReacquire(@Nullable LivingEntity target) {
        if (target != null) {
            faceTarget(target, REACQUIRE_TURN);
        }
        this.steering = this.steering.scale(0.85);
        applyMotion(this.steering, hoverRise(target));

        var duration = this.visage.isDivinePursuit() ? REACQUIRE_TICKS_PHASE_TWO : REACQUIRE_TICKS;
        if (this.visage.stateTimer() >= duration) {
            this.visage.setVisageState(VisageState.PURSUIT);
        }
    }

    // --- Divine Pursuit --------------------------------------------------------------------------

    private void beginDivinePursuitTransition() {
        this.visage.attacks().cancel();
        this.visage.setDivinePursuitStarted();
        this.visage.setVisageState(VisageState.DIVINE_PURSUIT_TRANSITION);
        this.visage.resetStateTimer();
        this.move = null;
        this.transitionStage = 0;
        this.transitionPass = 0;
        this.visage.level().playSound(null, this.visage.getX(), this.visage.getY(), this.visage.getZ(),
                ModSounds.VISAGE_ASCEND.get(), SoundSource.HOSTILE, 1.6f, 0.85f);
    }

    /// The cinematic: she climbs, makes three telegraphed high-speed passes using the shared charge-path
    /// attack, then drops back to combat altitude permanently more aggressive.
    ///
    /// Stage 0 climb, 1 line up a pass, 2 fly it, 3 settle. If anything about the passes fails (no target,
    /// timeout) the transition still ends with Divine Pursuit active — the permanent phase behaviour is
    /// the part that matters, the passes are showmanship.
    private void tickTransition(@Nullable LivingEntity target) {
        var runner = this.visage.attacks();
        switch (this.transitionStage) {
            case 0 -> {
                var base = target != null ? target.getY()
                        : groundHeight(this.visage.getX(), this.visage.getZ());
                var high = this.visage.getY() >= base + TRANSITION_RISE_HEIGHT;
                this.steering = this.steering.scale(0.8);
                this.visage.setDeltaMovement(this.steering.x, high ? 0.0 : TRANSITION_RISE_SPEED,
                        this.steering.z);
                if (target != null) {
                    faceTarget(target, PURSUIT_TURN);
                }
                if (high || this.visage.stateTimer() >= TRANSITION_RISE_TICKS) {
                    this.transitionStage = 1;
                    this.visage.resetStateTimer();
                }
            }
            case 1 -> {
                if (target == null || this.transitionPass >= TRANSITION_PASSES) {
                    this.transitionStage = 3;
                    this.visage.resetStateTimer();
                    return;
                }
                beginCharge(target);
                runner.start(VisageAttacks.TRANSITION_PASS);
                // beginCharge sets CHARGE; the transition owns the state until it is done.
                this.visage.setVisageState(VisageState.DIVINE_PURSUIT_TRANSITION);
                this.transitionPass++;
                this.transitionStage = 2;
                this.visage.resetStateTimer();
            }
            case 2 -> {
                var execution = runner.current();
                if (execution == null) {
                    this.transitionStage = 1;
                    this.visage.resetStateTimer();
                    return;
                }
                if (execution.phase() == AttackPhase.ACTIVE) {
                    var travelled = horizontal(this.visage.position().subtract(this.chargeStart)).length();
                    if (travelled >= this.chargeDistance) {
                        runner.cancel();
                        this.transitionStage = 1;
                        this.visage.resetStateTimer();
                        return;
                    }
                    this.steering = this.chargeDirection.scale(CHARGE_SPEED * 1.15);
                    applyMotion(this.steering, 0.0);
                    this.visage.setBank(28.0f);
                } else {
                    this.steering = this.steering.scale(0.7);
                    applyMotion(this.steering, 0.0);
                    if (target != null && execution.phase() == AttackPhase.WINDUP) {
                        faceTarget(target, PURSUIT_TURN);
                    }
                }
            }
            default -> {
                if (target != null) {
                    faceTarget(target, PURSUIT_TURN);
                }
                this.steering = this.steering.scale(0.8);
                applyMotion(this.steering, hoverRise(target));
                if (this.visage.stateTimer() >= TRANSITION_SETTLE_TICKS) {
                    this.visage.setDivinePursuitActive();
                    beginReacquire();
                }
            }
        }
    }

    // --- attack selection --------------------------------------------------------------------------

    /// Weighted choice over everything currently legal. Distance picks the shape of the answer, cooldowns
    /// and recent history stop it repeating, and randomness keeps it from being a lookup table.
    private @Nullable DirectionalAttack chooseAttack(LivingEntity target) {
        var distance = horizontal(target.position().subtract(this.visage.position())).length();
        var angle = angleToTarget(target);
        var weights = new LinkedHashMap<DirectionalAttack, Double>();

        // Excessive distance: close it violently rather than becoming a ranged boss.
        if (distance > CHARGE_TRIGGER_DIST) {
            if (available(VisageAttacks.VALKYRIES_PASSAGE)) {
                return VisageAttacks.VALKYRIES_PASSAGE;
            }
            if (available(VisageAttacks.JUDGMENT_FALL_IMPACT) && distance > CHARGE_TRIGGER_DIST + 3.0) {
                return VisageAttacks.JUDGMENT_FALL_IMPACT;
            }
            return null; // nothing off cooldown — keep flying at them
        }

        // Everything below is frontal; if she isn't facing them she turns or repositions this tick.
        if (angle > FRONTAL_CONE) {
            return null;
        }

        if (distance <= SLIP_DIST + 0.3) {
            weight(weights, VisageAttacks.PRESSING_ADVANCE, 6.0);
            weight(weights, VisageAttacks.WING_SWEEP, 2.5);
            weight(weights, VisageAttacks.DESCENDING_CUT, 1.5);
        } else if (distance <= 4.2) {
            weight(weights, VisageAttacks.WING_SWEEP, 4.0);
            weight(weights, VisageAttacks.DESCENDING_CUT, 3.0);
            weight(weights, VisageAttacks.LANCE_THRUST, 1.5);
            weight(weights, VisageAttacks.SUNDERING_SWEEP, 1.2);
        } else if (distance <= 7.0) {
            weight(weights, VisageAttacks.LANCE_THRUST, 4.5);
            weight(weights, VisageAttacks.WING_SWEEP, 1.0);
            weight(weights, VisageAttacks.SUNDERING_SWEEP, 1.0);
            weight(weights, VisageAttacks.HEAVENS_DIVIDE, 1.4);
        } else {
            weight(weights, VisageAttacks.LANCE_THRUST, 1.5);
            weight(weights, VisageAttacks.HEAVENS_DIVIDE, 2.0);
            weight(weights, VisageAttacks.VALKYRIES_PASSAGE, 2.0);
        }
        applyHistoryPenalty(weights);
        return pickWeighted(weights);
    }

    private void weight(Map<DirectionalAttack, Double> weights, DirectionalAttack attack, double value) {
        if (available(attack)) {
            weights.put(attack, value);
        }
    }

    private boolean available(DirectionalAttack attack) {
        return this.cooldowns.getOrDefault(attack.id(), 0) <= 0;
    }

    /// Halve the weight of anything she used recently, and quarter the weight of the last thing she did.
    private void applyHistoryPenalty(Map<DirectionalAttack, Double> weights) {
        var index = 0;
        for (var recent : this.history) {
            var penalty = index == 0 ? REPEAT_PENALTY : RECENT_PENALTY;
            weights.computeIfPresent(recent, (attack, value) -> value * penalty);
            index++;
        }
    }

    private <T> @Nullable T pickWeighted(Map<T, Double> weights) {
        var total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0.0) {
            return null;
        }
        var roll = this.random.nextDouble() * total;
        for (var entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll <= 0.0) {
                return entry.getKey();
            }
        }
        return null;
    }

    // --- launching ---------------------------------------------------------------------------------

    /// Route the chosen attack into whichever state owns it. The two that are as much movement as they
    /// are damage (the charge, the dive) get their own states; everything else is a plain swing.
    private void launch(DirectionalAttack attack, LivingEntity target) {
        this.cooldowns.put(attack.id(), cooldownFor(attack));
        rememberAttack(attack);
        this.chainNext = chainFor(attack);
        this.lastActionWasReposition = false;
        this.ticksInPlayerFront = 0;

        if (attack == VisageAttacks.VALKYRIES_PASSAGE) {
            beginCharge(target);
            this.visage.attacks().start(attack);
            return;
        }
        if (attack == VisageAttacks.JUDGMENT_FALL_IMPACT) {
            beginJudgmentFall(target);
            return;
        }
        if (this.visage.attacks().start(attack)) {
            this.visage.setVisageState(VisageState.ATTACK_WINDUP);
        } else {
            this.visage.setVisageState(VisageState.PURSUIT);
        }
    }

    private void rememberAttack(DirectionalAttack attack) {
        this.history.addFirst(attack);
        while (this.history.size() > HISTORY_SIZE) {
            this.history.removeLast();
        }
    }

    /// Per-attack cooldowns. Strong attacks are periodic punctuation; the basics are the rhythm.
    private int cooldownFor(DirectionalAttack attack) {
        var base = 0;
        if (attack == VisageAttacks.PRESSING_ADVANCE) {
            base = 30;
        } else if (attack == VisageAttacks.LANCE_THRUST || attack == VisageAttacks.WING_SWEEP) {
            base = 26;
        } else if (attack == VisageAttacks.DESCENDING_CUT) {
            base = 44;
        } else if (attack == VisageAttacks.SUNDERING_SWEEP) {
            base = 150;
        } else if (attack == VisageAttacks.HEAVENS_DIVIDE) {
            base = 190;
        } else if (attack == VisageAttacks.VALKYRIES_PASSAGE) {
            base = 140;
        } else if (attack == VisageAttacks.JUDGMENT_FALL_IMPACT) {
            base = 220;
        }
        return this.visage.isDivinePursuit() ? (int) (base * 0.7) : base;
    }

    /// Divine Pursuit follow-ups. Chains only fire in phase two, and only into moves whose telegraph is
    /// short enough to stay readable off the back of another attack.
    private @Nullable DirectionalAttack chainFor(DirectionalAttack attack) {
        if (!this.visage.isDivinePursuit() || this.random.nextFloat() > 0.55f) {
            return null;
        }
        if (attack == VisageAttacks.LANCE_THRUST) {
            return VisageAttacks.WING_SWEEP;
        }
        if (attack == VisageAttacks.WING_SWEEP) {
            return VisageAttacks.DESCENDING_CUT;
        }
        return null;
    }

    // --- geometry helpers --------------------------------------------------------------------------

    /// Turn the body toward the target, capped per tick. Yaw is synced, so the client mirrors it into
    /// body rotation for the renderer — the same trick the Raven uses.
    private void faceTarget(LivingEntity target, float maxDegrees) {
        var dx = target.getX() - this.visage.getX();
        var dz = target.getZ() - this.visage.getZ();
        var desired = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        var step = Mth.clamp(Mth.wrapDegrees(desired - this.visage.getYRot()), -maxDegrees, maxDegrees);
        var yaw = Mth.wrapDegrees(this.visage.getYRot() + step);
        this.visage.setYRot(yaw);
        this.visage.setYBodyRot(yaw);
        this.visage.setYHeadRot(yaw);

        // Mirror the aim into xRot so the model leans with it.
        //
        // This is presentation only. Vanilla's LookControl zeroes xRot every tick after custom AI
        // runs, so it is written fresh here and nothing is allowed to depend on it surviving — the
        // attack geometry reads the entity's own aim channel instead. See AttackAim.
        this.visage.setXRot(this.visage.attackPitch());
    }

    private float angleToTarget(LivingEntity target) {
        var dx = target.getX() - this.visage.getX();
        var dz = target.getZ() - this.visage.getZ();
        var desired = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(Mth.wrapDegrees(desired - this.visage.getYRot()));
    }

    private Vec3 facing() {
        var look = horizontal(this.visage.getLookAngle());
        return look.lengthSqr() < 1.0e-4 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
    }

    private double groundHeight(double x, double z) {
        return this.visage.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(x), Mth.floor(z));
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    private static Vec3 clampLength(Vec3 vector, double max) {
        var lengthSqr = vector.lengthSqr();
        return lengthSqr > max * max ? vector.scale(max / Math.sqrt(lengthSqr)) : vector;
    }
}
