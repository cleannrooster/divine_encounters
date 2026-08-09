package com.cleannrooster.divineencounters.content.malice.ai;

import com.cleannrooster.divineencounters.combat.AttackFrame;
import com.cleannrooster.divineencounters.combat.AttackPhase;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import com.cleannrooster.divineencounters.combat.TrackingMode;
import com.cleannrooster.divineencounters.content.malice.MalicePhasing;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.encounter.presence.ManifestKind;
import com.cleannrooster.divineencounters.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/// Behaviour director for the Visage of Malice, ticked once per server tick.
///
/// The whole class is organised around one split that does not exist in any other boss in the mod:
///
/// - **While observed** it fights in the open, by conventional rules. It holds a circling distance,
///   closes, attacks, and — critically — *works to break your line of sight*, because that is the
///   only way it unlocks anything.
/// - **While unresolved** it has no position. It cannot be fought and cannot fight. All it does is
///   leave ambiguous evidence and decide when to become real again.
///
/// The player's objective is to keep it in the first mode. Its objective is to reach the second.
/// Everything below serves that one conflict.
///
/// ### Movement is a glide, not a gait
/// Malice hovers. Its legs are scenery. Everything below steers a body that is being *translated*
/// through space rather than pushing itself through it — smooth, frictionless, and only loosely
/// connected to its own anatomy.
///
/// That is still a different privilege from the Visage of War's flight: War flies to close distance
/// and is always exactly where you can see her. Malice glides to hold position, and its real
/// advantage only begins when you stop looking.
///
/// Two rotations, deliberately decoupled:
/// - **gaze** snaps to the target almost immediately, and is what the head follows;
/// - **body yaw** eases toward it far more slowly, on a continuous arc.
///
/// The gap between them is the whole effect. The face keeps regarding you while the body swivels
/// beneath it like something mounted on a bearing.
public final class MaliceController {
    // --- stalking ----------------------------------------------------------------------------------
    /// Preferred circling distance. Close enough to threaten, far enough to slip out of a cone.
    private static final double STALK_MIN = 4.0, STALK_MAX = 6.5;
    private static final double CLOSE_SPEED = 0.34, STALK_SPEED = 0.22, EDGE_SPEED = 0.40;
    /// Low: velocity eases toward its target rather than snapping. This is the frictionless read —
    /// raising it immediately makes the movement feel muscular again.
    private static final double ACCEL = 0.12;
    /// Body yaw, degrees per tick. Deliberately slow and constant, so turns are continuous arcs
    /// rather than the stepped corrections a walking creature makes.
    private static final float BODY_TURN = 4.5f;
    /// Gaze, degrees per tick. Fast enough to stay locked through anything short of a teleport.
    private static final float GAZE_TURN = 42.0f;
    /// Degrees of roll per unit of lateral speed.
    private static final float BANK_PER_SPEED = 62.0f;

    // --- hover -------------------------------------------------------------------------------------
    /// Held tightly. The more consistent the hover, the more a deliberate drop or rise reads as an
    /// event rather than as noise.
    private static final double HOVER_HEIGHT = 1.15;
    private static final double HOVER_GAIN = 0.28, HOVER_MAX_SPEED = 0.5, HOVER_DAMP = 0.72;
    /// Almost imperceptible. Present only so it is not literally frozen; a visible bob reads as
    /// breathing, which is exactly what this pass is removing.
    private static final double HOVER_DRIFT = 0.045;
    /// How far above the target's own feet the hover will not sink below.
    ///
    /// The Visage of War has had a dynamic combat floor since its first pass; Malice did not, and the
    /// omission is why its vegetation phasing looked broken. Holding a fixed offset above the terrain
    /// meant it could never rise, so it never entered a canopy, so the phasing had nothing to phase
    /// through — the rule was working and simply never came up. It also meant a player who climbed a
    /// tree was safe from it, which the encounter very much does not intend.
    private static final double COMBAT_FLOOR_MARGIN = 0.2;
    /// Frontal attacks are refused outside this half-angle.
    private static final float FRONTAL_CONE = 50.0f;

    // --- breaking contact --------------------------------------------------------------------------
    /// How long it will commit to a contact-break attempt before giving up and fighting normally.
    private static final int BREAK_MAX_TICKS = 50;
    /// It only bothers trying to break contact once it has been stared at this long.
    ///
    /// Raised substantially in the "hostile concept" pass. It does not scurry out of sight the
    /// moment it is looked at — staying calmly, impossibly close *is* the threat, and a boss that
    /// flees on sight stops being frightening and starts being an errand. Breaking contact should
    /// usually happen because the player turned, not because the boss solved its own mechanic.
    private static final int STARED_AT_PATIENCE = 200;
    private static final int BREAK_COOLDOWN = 90;
    private static final double BREAK_SPEED = 0.52;

    // --- pacing ------------------------------------------------------------------------------------
    private static final int ATTACK_GAP = 16;
    private static final double REPEAT_PENALTY = 0.25, RECENT_PENALTY = 0.55;
    private static final int HISTORY_SIZE = 3;

    // --- unresolved --------------------------------------------------------------------------------
    /// Minimum time hidden before it will consider striking — a dissolve immediately followed by an
    /// ambush would read as a teleport attack.
    private static final int MIN_HIDDEN_TICKS = 24;
    /// Once hidden this long it becomes increasingly likely to commit, so it can never simply
    /// refuse to fight.
    private static final int PATIENCE_HIDDEN_TICKS = 90;
    /// Chance per tick that a camera sweep flushes it out into the open.
    private static final float SEARCH_REVEAL_CHANCE = 0.012f;

    /// Presence the player is owed after it commits to something.
    ///
    /// Refreshed every tick an attack is running, so it also covers the whole attack — the boss
    /// cannot dissolve out of its own recovery, which is the punish window the fight is built to
    /// hand over. Together with the resolved-time floor in the presence controller, an ambush is
    /// always followed by a real opportunity to answer it.
    private static final int PUNISH_WINDOW = 40;

    /// Hard ceiling on time spent with no position.
    ///
    /// The eagerness ramp already makes it increasingly likely to commit, but "increasingly likely"
    /// still has a tail, and a tail is what turns an encounter into a search. Past this it comes
    /// back whether it likes the angle or not.
    private static final int MAX_HIDDEN_TICKS = 110;

    // --- Grudge ------------------------------------------------------------------------------------
    private static final int GRUDGE_DELAY = 55;

    private final VisageOfMaliceEntity malice;
    private final RandomSource random;

    private int attackGap;
    private int breakCooldown;
    private int breakTicks;
    private final Map<ResourceLocation, Integer> cooldowns = new HashMap<>();
    private final Deque<DirectionalAttack> history = new ArrayDeque<>();

    private Vec3 steering = Vec3.ZERO;
    /// Which way it circles. Flipped occasionally so it cannot be herded.
    private int orbitDirection = 1;

    /// A pending Grudge: where the mark was left and when it detonates.
    private @Nullable Vec3 grudgeMark;
    private int grudgeTimer;

    /// The attack queued to fire the moment a requested manifestation finishes arriving.
    private @Nullable DirectionalAttack pendingAmbush;

    /// The one-time No Witness movement, once it has begun.
    private @Nullable NoWitnessSequence noWitness;

    /// Whether an attack was running last tick, so the punish window can be granted on the edge.
    private boolean wasAttacking;

    public MaliceController(VisageOfMaliceEntity malice) {
        this.malice = malice;
        this.random = malice.getRandom();
    }

    // --- main tick ---------------------------------------------------------------------------------

    public void serverTick() {
        if (!(this.malice.level() instanceof ServerLevel level)) {
            return;
        }
        tickCooldowns();
        this.malice.attacks().tick();

        var target = validTarget();
        this.fightTarget = target;
        var participants = this.malice.participants();
        this.malice.presence().tick(level, participants, this.malice.gloom(), target);

        // Vertical aim, updated before anything can commit an attack. Deliberately separate from the
        // body, which stays rigidly level: a swipe at a player on a ledge tilts to reach them without
        // the creature itself ever leaning, which is if anything *more* wrong-looking than leaning
        // would be.
        this.malice.aimAt(target);

        // Anything it has committed to keeps it here, plus a window afterwards.
        //
        // The punish window is granted once, on the tick the attack *ends* — not refreshed while it
        // runs. Refreshing was the obvious way to write it and it deadlocks: a 40-tick window topped
        // up every busy tick only starts draining when the attack finishes, and with a 16-tick gap
        // the next attack always renewed it first. The hold never reached zero and the boss could
        // never dissolve at all.
        //
        // While an attack is running it holds by a single tick, which is enough to forbid vanishing
        // mid-swing and cannot accumulate.
        var attacking = this.malice.attacks().isBusy();
        if (attacking) {
            this.malice.presence().holdResolved(1);
        } else if (this.wasAttacking) {
            this.malice.presence().holdResolved(PUNISH_WINDOW);
        }
        this.wasAttacking = attacking;

        tickGrudge(level);

        if (tickNoWitness(level, target)) {
            return;
        }

        if (this.malice.presence().isUnresolved()) {
            this.malice.setMaliceState(MaliceState.HIDDEN);
            tickHidden(level, target);
            return;
        }

        // Anything below here has a real position.
        if (target == null) {
            decelerate();
            this.malice.setMaliceState(MaliceState.STALK);
            return;
        }

        if (this.malice.attacks().isBusy()) {
            tickAttacking(target);
            return;
        }
        if (this.pendingAmbush != null && this.malice.presence().mayAttack()) {
            var attack = this.pendingAmbush;
            this.pendingAmbush = null;
            launch(level, attack, target);
            return;
        }
        tickVisible(level, target);
    }

    private @Nullable Player validTarget() {
        var target = this.malice.getTarget();
        return target instanceof Player player && player.isAlive() ? player : null;
    }

    private void tickCooldowns() {
        this.cooldowns.replaceAll((id, value) -> Math.max(0, value - 1));
        if (this.attackGap > 0) {
            this.attackGap--;
        }
        if (this.breakCooldown > 0) {
            this.breakCooldown--;
        }
        if (this.malice.tickCount % 71 == 0) {
            this.orbitDirection = this.random.nextBoolean() ? 1 : -1;
        }
    }

    // --- observed behaviour ------------------------------------------------------------------------

    /// Ordinary ground combat, plus the constant search for a way out of the player's cone.
    private void tickVisible(ServerLevel level, Player target) {
        var observation = this.malice.presence().observation();

        // Being stared at for a long time is the cue to stop trading and start hunting for cover.
        var pinned = observation.observedTicks() >= STARED_AT_PATIENCE;
        if (this.malice.getMaliceState() == MaliceState.BREAK_CONTACT) {
            tickBreakContact(target);
            return;
        }
        if (pinned && this.breakCooldown <= 0 && this.attackGap > 0) {
            beginBreakContact();
            return;
        }

        this.malice.setMaliceState(MaliceState.STALK);
        faceTarget(target, BODY_TURN);
        stalkAround(target);

        if (this.attackGap <= 0) {
            var attack = chooseVisibleAttack(target);
            if (attack != null) {
                launch(level, attack, target);
            }
        }
    }

    /// Circle at a threatening distance rather than charging straight in. Orbiting is what
    /// eventually carries it toward the edge of the player's vision.
    private void stalkAround(Player target) {
        var toTarget = horizontal(target.position().subtract(this.malice.position()));
        var distance = toTarget.length();
        var forward = distance < 1.0e-4 ? facing() : toTarget.normalize();
        var side = new Vec3(-forward.z, 0.0, forward.x).scale(this.orbitDirection);

        double radial;
        if (distance > STALK_MAX) {
            radial = CLOSE_SPEED;
        } else if (distance < STALK_MIN) {
            radial = -STALK_SPEED * 0.8;
        } else {
            radial = STALK_SPEED * 0.25;
        }
        accelerateTo(forward.scale(radial).add(side.scale(STALK_SPEED)));
        applySteering();
    }

    /// A committed run for the player's blind side. This is the honest, physical way it earns the
    /// right to disappear — no shortcuts, and fully visible while it happens.
    private void beginBreakContact() {
        this.malice.setMaliceState(MaliceState.BREAK_CONTACT);
        this.breakTicks = 0;
        this.breakCooldown = BREAK_COOLDOWN;
        this.orbitDirection = this.random.nextBoolean() ? 1 : -1;
    }

    private void tickBreakContact(Player target) {
        this.breakTicks++;
        var toTarget = horizontal(target.position().subtract(this.malice.position()));
        var distance = toTarget.length();
        var forward = distance < 1.0e-4 ? facing() : toTarget.normalize();
        var side = new Vec3(-forward.z, 0.0, forward.x).scale(this.orbitDirection);

        // Sprint hard around the player's flank, keeping enough distance that a quick turn does not
        // immediately re-acquire.
        var radial = distance < STALK_MIN ? -0.35 : distance > STALK_MAX + 2.0 ? 0.2 : 0.0;
        accelerateTo(side.scale(BREAK_SPEED).add(forward.scale(radial)));
        applySteering();
        faceTarget(target, BODY_TURN * 0.6f);

        // Either it worked — presence will dissolve on its own — or it has spent long enough.
        if (this.breakTicks >= BREAK_MAX_TICKS
                || !this.malice.presence().observation().isObserved()) {
            this.malice.setMaliceState(MaliceState.STALK);
        }
    }

    private void tickAttacking(Player target) {
        var execution = this.malice.attacks().current();
        if (execution == null) {
            this.attackGap = ATTACK_GAP;
            this.malice.setMaliceState(MaliceState.STALK);
            return;
        }
        this.malice.setMaliceState(switch (execution.phase()) {
            case WINDUP -> MaliceState.ATTACK_WINDUP;
            case ACTIVE -> MaliceState.ATTACK_ACTIVE;
            case RECOVERY, FINISHED -> MaliceState.ATTACK_RECOVERY;
        });

        // Tracking is capped by the attack definition, never by this class — the same contract the
        // Visage of War uses, so a committed swing stays dodgeable.
        var tracking = this.malice.attacks().tracking();
        if (tracking != TrackingMode.LOCKED) {
            faceTarget(target, tracking.degreesPerTick());
        }
        decelerate();
    }

    // --- No Witness ---------------------------------------------------------------------------------

    /// Drive the phase-transition sequence. Returns true while it owns the boss's behaviour.
    ///
    /// It is started here rather than inside the sequence so the trigger condition lives with the
    /// rest of the AI's decision-making, and so the sequence itself stays a pure script.
    private boolean tickNoWitness(ServerLevel level, @Nullable Player target) {
        if (this.noWitness == null) {
            var ratio = this.malice.getHealth() / this.malice.getMaxHealth();
            if (this.malice.noWitnessTriggered() || ratio > 0.4f) {
                return false;
            }
            this.noWitness = new NoWitnessSequence(this.malice);
            this.noWitness.begin(level);
            this.malice.attacks().cancel();
            return true;
        }
        if (this.noWitness.isFinished()) {
            return false;
        }
        this.malice.setMaliceState(MaliceState.NO_WITNESS);

        // The sequence only ever *proposes* an attack; starting it still goes through the normal
        // path, so the fairness chain cannot be bypassed here.
        var proposed = this.noWitness.tick(level, target, this.random);
        if (proposed != null) {
            this.pendingAmbush = proposed;
        }
        // Once a manifestation has landed, fire whatever it was requested for.
        if (this.pendingAmbush != null && this.malice.presence().mayAttack()
                && !this.malice.attacks().isBusy() && target != null
                && !this.malice.isEmbeddedInTrunk()) {
            var attack = this.pendingAmbush;
            this.pendingAmbush = null;
            this.cooldowns.put(attack.id(), cooldownFor(attack));
            remember(attack);
            this.malice.attacks().start(attack);
        }
        if (!this.malice.attacks().isBusy() && this.malice.presence().isResolved() && target != null) {
            // Between strikes it behaves normally, which means it tries to get out of sight again.
            tickVisible(level, target);
        }
        return true;
    }

    // --- unresolved behaviour ----------------------------------------------------------------------

    /// While it has no position, the only decisions are *whether* to become real and *what for*.
    private void tickHidden(ServerLevel level, @Nullable Player target) {
        var presence = this.malice.presence();
        var hidden = presence.ticksInState();

        if (target == null) {
            // Nobody to hunt: come back rather than lurk forever.
            if (hidden > PATIENCE_HIDDEN_TICKS) {
                presence.forceManifestation(level);
            }
            return;
        }
        if (hidden < MIN_HIDDEN_TICKS) {
            return;
        }
        // Out of patience. Come back and fight.
        if (hidden >= MAX_HIDDEN_TICKS) {
            launchAmbush(level, target);
            return;
        }

        // A player sweeping the arena occasionally finds it — but only occasionally, or searching
        // would be a reliable counter and hiding would mean nothing.
        if (presence.mayResolveOnSearch(this.random, SEARCH_REVEAL_CHANCE)) {
            presence.forceManifestation(level);
            return;
        }

        // The longer it waits, the more willing it is to commit. It can never simply refuse to
        // fight, and a stalemate is not an available outcome.
        var eagerness = Mth.clamp((hidden - MIN_HIDDEN_TICKS)
                / (float) (PATIENCE_HIDDEN_TICKS - MIN_HIDDEN_TICKS), 0.0f, 1.0f);
        if (this.random.nextFloat() > eagerness * 0.09f) {
            return;
        }
        launchAmbush(level, target);
    }

    /// Pick an ambush the current candidate pool can actually satisfy, then ask presence to resolve
    /// there. The attack itself is queued and fires once the manifestation finishes arriving.
    private void launchAmbush(ServerLevel level, Player target) {
        var presence = this.malice.presence();
        var weights = new LinkedHashMap<DirectionalAttack, Double>();

        for (var attack : new DirectionalAttack[]{MaliceAttacks.BACKBITE, MaliceAttacks.NEEDLE_THRUST,
                MaliceAttacks.HORN_RUSH, MaliceAttacks.POUNCE_STRIKE, MaliceAttacks.BLACK_SWEEP}) {
            var kind = MaliceAttacks.ambushKind(attack);
            if (kind == null || !available(attack) || !presence.canManifest(kind)) {
                continue;
            }
            weights.put(attack, switch (attack.id().getPath()) {
                case "backbite" -> 4.0;
                case "needle_thrust" -> 2.5;
                case "pounce_strike" -> 2.2;
                case "horn_rush" -> 1.6;
                default -> 1.0;
            });
        }
        applyHistoryPenalty(weights);

        var chosen = pickWeighted(weights);
        if (chosen == null) {
            return;
        }
        var kind = MaliceAttacks.ambushKind(chosen);
        if (kind == null || !presence.requestManifestation(level, kind)) {
            return;
        }
        // Manifestation plays the strong directional tell; the attack follows once it has arrived.
        this.pendingAmbush = chosen;
        this.malice.setMaliceState(MaliceState.AMBUSH);
    }

    // --- attack selection --------------------------------------------------------------------------

    private @Nullable DirectionalAttack chooseVisibleAttack(Player target) {
        if (!this.malice.presence().mayAttack()) {
            return null;
        }
        var distance = horizontal(target.position().subtract(this.malice.position())).length();
        var angle = angleToTarget(target);
        var weights = new LinkedHashMap<DirectionalAttack, Double>();

        // A player who has run behind it gets the backhand rather than a slow turn.
        if (angle > 110.0f) {
            weight(weights, MaliceAttacks.RAKING_BACKHAND, 5.0);
            applyHistoryPenalty(weights);
            return pickWeighted(weights);
        }
        if (angle > FRONTAL_CONE) {
            return null;
        }

        if (distance <= 3.2) {
            weight(weights, MaliceAttacks.HOOKING_SWIPE, 4.5);
            weight(weights, MaliceAttacks.CRESCENT_OF_SPITE, 1.2);
            weight(weights, MaliceAttacks.RAKING_BACKHAND, 0.8);
        } else if (distance <= 5.0) {
            weight(weights, MaliceAttacks.HOOKING_SWIPE, 2.0);
            weight(weights, MaliceAttacks.NEEDLE_THRUST, 2.5);
            weight(weights, MaliceAttacks.LOW_POUNCE, 1.6);
            weight(weights, MaliceAttacks.CRESCENT_OF_SPITE, 1.4);
        } else if (distance <= 8.0) {
            weight(weights, MaliceAttacks.LOW_POUNCE, 3.0);
            weight(weights, MaliceAttacks.HORN_RUSH, 2.4);
            weight(weights, MaliceAttacks.GRUDGE, 1.2);
        } else {
            weight(weights, MaliceAttacks.HORN_RUSH, 2.5);
            weight(weights, MaliceAttacks.GRUDGE, 1.5);
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

    private void applyHistoryPenalty(Map<DirectionalAttack, Double> weights) {
        var index = 0;
        for (var recent : this.history) {
            var penalty = index == 0 ? REPEAT_PENALTY : RECENT_PENALTY;
            weights.computeIfPresent(recent, (attack, value) -> value * penalty);
            index++;
        }
    }

    private @Nullable DirectionalAttack pickWeighted(Map<DirectionalAttack, Double> weights) {
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

    private void launch(ServerLevel level, DirectionalAttack attack, Player target) {
        // Never from inside a tree. See VisageOfMaliceEntity#isEmbeddedInTrunk — passing through a
        // trunk is a traversal privilege, not a firing position, and a strike out of solid wood is
        // one the player cannot see coming or answer. Checked before the cooldown is consumed so a
        // refused attack costs nothing and can be chosen again a tick later.
        if (this.malice.isEmbeddedInTrunk()) {
            return;
        }
        this.cooldowns.put(attack.id(), cooldownFor(attack));
        remember(attack);
        this.attackGap = ATTACK_GAP;

        // Grudge does not swing at anything — it leaves a mark and comes back to it later.
        if (attack == MaliceAttacks.GRUDGE) {
            markGrudge(level, target);
            return;
        }
        if (this.malice.attacks().start(attack)) {
            this.malice.setMaliceState(MaliceState.ATTACK_WINDUP);
        }
    }

    private void remember(DirectionalAttack attack) {
        this.history.addFirst(attack);
        while (this.history.size() > HISTORY_SIZE) {
            this.history.removeLast();
        }
    }

    private int cooldownFor(DirectionalAttack attack) {
        return switch (attack.id().getPath()) {
            case "hooking_swipe" -> 26;
            case "raking_backhand" -> 50;
            case "low_pounce" -> 60;
            case "needle_thrust" -> 44;
            case "horn_rush" -> 110;
            case "backbite" -> 120;
            case "black_sweep" -> 190;
            case "grudge" -> 160;
            case "crescent_of_spite" -> 170;
            case "pounce_strike" -> 150;
            default -> 40;
        };
    }

    // --- Grudge ------------------------------------------------------------------------------------

    /// Leave a mark where the player is standing. The counterplay is simply to not be there later.
    private void markGrudge(ServerLevel level, Player target) {
        this.grudgeMark = target.position();
        this.grudgeTimer = GRUDGE_DELAY;
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                ModSounds.MALICE_SCRAPE.get(), SoundSource.HOSTILE, 0.8f, 0.6f);
    }

    private void tickGrudge(ServerLevel level) {
        var mark = this.grudgeMark;
        if (mark == null) {
            return;
        }
        // A subtle, steady imprint — readable without being a puzzle.
        if (this.grudgeTimer % 4 == 0) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK,
                    mark.x, mark.y + 0.1, mark.z, 4, 0.5, 0.02, 0.5, 0.0);
        }
        if (--this.grudgeTimer > 0) {
            return;
        }
        this.grudgeMark = null;
        // Resolves at the mark rather than at Malice — the whole point of the attack.
        var frame = new AttackFrame(mark.add(0.0, 0.2, 0.0), this.malice.getYRot(), 0.0f, 0.0f, false,
                this.malice.getScale());
        this.malice.attacks().startAt(MaliceAttacks.GRUDGE, frame);
    }

    // --- movement helpers --------------------------------------------------------------------------

    private void accelerateTo(Vec3 desired) {
        this.steering = this.steering.add(desired.subtract(this.steering).scale(ACCEL));
    }

    /// Apply the steered velocity, hold the hover, and bank into whatever lateral motion resulted.
    private void applySteering() {
        this.malice.setDeltaMovement(this.steering.x, hoverRise(), this.steering.z);
        updateBank();
    }

    /// Ease to a stop rather than braking. Nothing here should look like it is applying force to
    /// itself.
    private void decelerate() {
        this.steering = this.steering.scale(0.86);
        applySteering();
    }

    /// Vertical velocity holding a near-constant height above whatever is beneath it.
    ///
    /// The damping is what sells it. A stiff spring would make the hover twitch over uneven ground,
    /// which reads as effort; this lags, so crossing a step looks like the *ground* changed rather
    /// than like the boss adjusted.
    private double hoverRise() {
        var drift = Math.sin(this.malice.tickCount * 0.021) * HOVER_DRIFT;
        var desiredY = surfaceBelow() + HOVER_HEIGHT + drift;

        // Never below the player's own level. A `max` rather than a follow, so it rises to meet
        // someone who has climbed without diving after someone who has dropped into a hole — and so
        // the hover stays a held offset rather than becoming a chase.
        //
        // This is what puts it into the canopy in the first place, and therefore what makes the
        // vegetation phasing visible at all.
        var target = this.fightTarget;
        if (target != null && target.isAlive()) {
            desiredY = Math.max(desiredY, target.getY() + COMBAT_FLOOR_MARGIN);
        }

        var rise = Mth.clamp((desiredY - this.malice.getY()) * HOVER_GAIN,
                -HOVER_MAX_SPEED, HOVER_MAX_SPEED);
        return this.malice.getDeltaMovement().y * HOVER_DAMP + rise * (1.0 - HOVER_DAMP);
    }

    /// The player the hover height is measured against, refreshed each tick in {@link #serverTick}.
    ///
    /// A field rather than a parameter because `hoverRise` is reached through `applySteering` from
    /// several states, and threading a target through all of them to reach one `max` would be a lot
    /// of churn for one number.
    private @Nullable Player fightTarget;

    /// Y of the first solid surface below the boss.
    ///
    /// Scanning downward rather than reading the heightmap, because the heightmap answers a
    /// different question: it reports the highest solid block in the column, which inside the
    /// roofed cage arena is the *ceiling*. Using it there would have Malice climb to hover a metre
    /// above the roof of its own arena. Falls back to the heightmap only if the scan finds nothing
    /// within range, which means it is over a void or a very deep drop.
    private double surfaceBelow() {
        var pos = new BlockPos.MutableBlockPos(Mth.floor(this.malice.getX()),
                Mth.floor(this.malice.getY()), Mth.floor(this.malice.getZ()));
        var level = this.malice.level();
        for (var drop = 0; drop <= HOVER_PROBE_DEPTH; drop++) {
            var state = level.getBlockState(pos);
            // Vegetation is not a floor. Without this the hover would settle on top of the canopy in
            // a dark forest — leaves have a full collision shape, so a naive scan reads a treetop as
            // solid ground and the boss rises out of the fight to hover above the trees.
            if (!MalicePhasing.canPhase(state, this.malice.phasesTrunks())
                    && !state.getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + 1.0;
            }
            pos.move(0, -1, 0);
            if (level.isOutsideBuildHeight(pos)) {
                break;
            }
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Mth.floor(this.malice.getX()), Mth.floor(this.malice.getZ()));
    }

    /// How far down to look for a floor. Comfortably deeper than the cage, shallow enough that
    /// hovering out over a chasm makes it descend rather than hang in the air like a platform.
    private static final int HOVER_PROBE_DEPTH = 24;

    /// Roll proportional to how much of the current motion is sideways relative to the facing. A
    /// body that banks into its own drift reads as mounted on a bearing rather than walking.
    private void updateBank() {
        var forward = facing();
        var side = new Vec3(-forward.z, 0.0, forward.x);
        this.malice.setBank((float) (-this.steering.dot(side) * BANK_PER_SPEED));
    }

    /// Aim the gaze immediately and let the body catch up in its own time.
    ///
    /// The decoupling is the point: this drives the head almost instantly, while the body yaw eases
    /// at a small fraction of the rate. While circling, the face simply keeps regarding the player
    /// as the body rotates underneath it.
    private void faceTarget(LivingEntity target, float maxBodyDegrees) {
        var dx = target.getX() - this.malice.getX();
        var dz = target.getZ() - this.malice.getZ();
        var desired = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;

        // Eye to eye, not eye to chest. Malice stands nearly five blocks tall, so it is looking
        // steeply down at anything human-sized and the difference between aiming at a player's head
        // and their midriff is the difference between being regarded and being inspected.
        var dy = target.getEyeY() - this.malice.getEyeY();
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        var desiredPitch = (float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI));

        // Gaze: near-instant, and the head follows this.
        var gazeStep = Mth.clamp(Mth.wrapDegrees(desired - this.malice.gazeYaw()),
                -GAZE_TURN, GAZE_TURN);
        this.malice.setGaze(this.malice.gazeYaw() + gazeStep, desiredPitch);

        // Body: a slow, continuous arc toward the same heading. No snapping, no correction steps.
        var bodyStep = Mth.clamp(Mth.wrapDegrees(desired - this.malice.getYRot()),
                -maxBodyDegrees, maxBodyDegrees);
        var yaw = Mth.wrapDegrees(this.malice.getYRot() + bodyStep);
        this.malice.setYRot(yaw);
        this.malice.setYBodyRot(yaw);
        this.malice.setYHeadRot(yaw);
        // Body pitch stays flat; the head carries all the visible aiming. Its *attacks* still tilt —
        // see the aim update in serverTick.
        this.malice.setXRot(0.0f);
    }

    private float angleToTarget(LivingEntity target) {
        var dx = target.getX() - this.malice.getX();
        var dz = target.getZ() - this.malice.getZ();
        var desired = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(Mth.wrapDegrees(desired - this.malice.getYRot()));
    }

    private Vec3 facing() {
        var look = horizontal(this.malice.getLookAngle());
        return look.lengthSqr() < 1.0e-4 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }
}
