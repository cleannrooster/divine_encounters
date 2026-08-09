package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.combat.net.AttackNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/// One running swing. Server-side only.
///
/// This is where the centralisation actually happens: the phase timing, the hit detection, the damage and
/// knockback, the sound cues, the attacker's advance, the tracking clamp and the client visual dispatch
/// are all driven from the {@link DirectionalAttack} definition here — once — for every attack in the
/// game. A boss's AI decides *which* attack and *when*; it never writes any of this again.
public final class AttackExecution {
    private final DirectionalAttack definition;
    private final LivingEntity attacker;
    private final Predicate<LivingEntity> targetFilter;
    private AttackFrame frame;

    private AttackPhase phase = AttackPhase.WINDUP;
    private int phaseTick;
    private int totalTick;

    /// Victims already hit this execution, mapped to the tick they were last hit (for multi-hit attacks).
    private final Map<Entity, Integer> hits = new HashMap<>();
    private float previousProgress;
    private @Nullable Vec3 previousOrigin;
    private boolean impactSoundPlayed;

    AttackExecution(DirectionalAttack definition, LivingEntity attacker, AttackFrame frame,
                    Predicate<LivingEntity> targetFilter) {
        this.definition = definition;
        this.attacker = attacker;
        this.frame = frame;
        this.targetFilter = targetFilter;
    }

    // --- accessors -------------------------------------------------------------------------------

    public DirectionalAttack definition() {
        return this.definition;
    }

    public LivingEntity attacker() {
        return this.attacker;
    }

    public AttackFrame frame() {
        return this.frame;
    }

    public AttackPhase phase() {
        return this.phase;
    }

    public int phaseTick() {
        return this.phaseTick;
    }

    public boolean isFinished() {
        return this.phase == AttackPhase.FINISHED;
    }

    /// Number of entities damaged so far — handy for hooks that care whether an attack whiffed.
    public int hitCount() {
        return this.hits.size();
    }

    /// How far the swing has travelled, 0-1. Zero during windup, 1 once the active window closes.
    public float progress() {
        return switch (this.phase) {
            case WINDUP -> 0.0f;
            case ACTIVE -> this.definition.activeTicks() <= 0
                    ? 1.0f
                    : Math.min(1.0f, (float) this.phaseTick / this.definition.activeTicks());
            case RECOVERY, FINISHED -> 1.0f;
        };
    }

    /// Turn rate cap the attacker should honour right now. The AI reads this instead of deciding for
    /// itself when a move is committed, so "you can dodge this" is a property of the attack definition.
    public TrackingMode tracking() {
        return switch (this.phase) {
            case WINDUP -> this.definition.windupTracking();
            case ACTIVE -> this.definition.activeTracking();
            case RECOVERY, FINISHED -> TrackingMode.MINIMAL;
        };
    }

    /// Velocity the attack wants to contribute this tick — the lunge on a thrust, the hop before a
    /// descending cut. The owning controller folds this into its own steering.
    public Vec3 motionThisTick() {
        return switch (this.phase) {
            case WINDUP -> this.definition.advanceLift() == 0.0 || this.definition.windupTicks() <= 0
                    ? Vec3.ZERO
                    : new Vec3(0.0, this.definition.advanceLift() / this.definition.windupTicks(), 0.0);
            case ACTIVE -> this.definition.advanceDistance() == 0.0 || this.definition.activeTicks() <= 0
                    ? Vec3.ZERO
                    : this.frame.forward().multiply(1.0, 0.0, 1.0).normalize()
                    .scale(this.definition.advanceDistance() / this.definition.activeTicks());
            case RECOVERY, FINISHED -> Vec3.ZERO;
        };
    }

    // --- lifecycle -------------------------------------------------------------------------------

    void begin() {
        this.definition.sounds().play(this.attacker.level(), this.frame.origin(),
                this.definition.sounds().windup(), this.attacker);
        this.definition.hooks().fireStart(this);
    }

    /// Advance one server tick. Returns false once the execution is done and can be dropped.
    boolean tick() {
        if (this.phase == AttackPhase.FINISHED) {
            return false;
        }
        this.totalTick++;

        // A travelling attack re-anchors to the attacker each tick; everything else stays frozen where it
        // committed, which is what lets the player sidestep it.
        if (this.definition.shape().followsAttacker()) {
            this.previousOrigin = this.frame.origin();
            this.frame = this.frame.withOrigin(this.attacker.position()
                    .add(0.0, this.definition.offsetVertical(), 0.0));
        }

        // Each phase counts its ticks first, then acts, so a phase declared as N ticks lasts exactly N
        // ticks and an attack with a zero-length phase skips it entirely rather than costing a tick.
        switch (this.phase) {
            case WINDUP -> {
                this.phaseTick++;
                if (this.phaseTick >= this.definition.windupTicks()) {
                    enterActive();
                }
            }
            case ACTIVE -> {
                this.phaseTick++;
                resolveActiveTick();
                if (this.phaseTick >= this.definition.activeTicks()) {
                    enterRecovery();
                }
            }
            case RECOVERY -> {
                this.phaseTick++;
                if (this.phaseTick >= this.definition.recoveryTicks()) {
                    this.phase = AttackPhase.FINISHED;
                    this.definition.hooks().fireFinish(this);
                    return false;
                }
            }
            case FINISHED -> {
                return false;
            }
        }
        return true;
    }

    private void enterActive() {
        this.phase = AttackPhase.ACTIVE;
        this.phaseTick = 0;
        this.previousProgress = 0.0f;
        this.previousOrigin = this.frame.origin();

        this.definition.sounds().play(this.attacker.level(), this.frame.origin(),
                this.definition.sounds().swingStart(), this.attacker);
        // The low layer lands with the swing, not with the telegraph — the windup is tension, the
        // release is where the weight belongs.
        this.definition.sounds().playWeight(this.attacker.level(), this.frame.origin());
        // One compact packet per swing: the id of a definition both sides already have, plus the frame.
        // Clients rebuild the arc, the trail and the particles from that.
        AttackNetwork.broadcastSwing(this.attacker, this.definition, this.frame);

        // The room reacts. Once per swing, on the first damaging tick — see ForceFeedback for why it
        // is ground dust rather than anything the boss owns.
        if (this.attacker.level() instanceof ServerLevel serverLevel) {
            ForceFeedback.displace(serverLevel, this.definition, this.frame.origin(),
                    AttackGeometry.strikeDirection(this.definition, this.frame, 0.5f));
        }

        this.definition.hooks().fireActiveStart(this);

        // Shapes that don't sweep resolve their whole volume on the first active tick.
        if (!this.definition.shape().isSwept()) {
            applyDamage(0.0f, 1.0f);
        }
    }

    private void enterRecovery() {
        this.phase = AttackPhase.RECOVERY;
        this.phaseTick = 0;
        this.definition.sounds().play(this.attacker.level(), this.frame.origin(),
                this.definition.sounds().recovery(), this.attacker);
        this.definition.hooks().fireActiveEnd(this);
    }

    private void resolveActiveTick() {
        if (!this.definition.shape().isSwept()) {
            // Already resolved on entry — but a travelling attack keeps sweeping as it moves.
            if (this.definition.shape().followsAttacker()) {
                applyDamage(0.0f, 1.0f);
            }
            return;
        }
        var progress = progress();
        applyDamage(this.previousProgress, progress);
        this.previousProgress = progress;
    }

    // --- damage ----------------------------------------------------------------------------------

    private void applyDamage(float from, float to) {
        if (!(this.attacker.level() instanceof ServerLevel level)) {
            return;
        }
        var bounds = AttackGeometry.bounds(this.definition, this.frame, this.previousOrigin);
        var candidates = level.getEntitiesOfClass(LivingEntity.class, bounds, this::isValidTarget);
        for (var victim : candidates) {
            if (!canHitAgain(victim)) {
                continue;
            }
            if (!AttackGeometry.hits(this.definition, this.frame, from, to,
                    victim.getBoundingBox(), this.previousOrigin)) {
                continue;
            }
            strike(victim);
        }
    }

    private boolean isValidTarget(LivingEntity candidate) {
        return candidate != this.attacker
                && candidate.isAlive()
                && !candidate.isSpectator()
                && !this.attacker.isAlliedTo(candidate)
                && this.targetFilter.test(candidate);
    }

    private boolean canHitAgain(LivingEntity victim) {
        var last = this.hits.get(victim);
        if (last == null) {
            return true;
        }
        return this.definition.multiHit()
                && this.totalTick - last >= this.definition.multiHitCooldown();
    }

    private void strike(LivingEntity victim) {
        this.hits.put(victim, this.totalTick);
        var source = this.definition.damageSource(this.attacker);
        var landed = AttackDamage.hurt(victim, source, this.definition.damage(),
                this.definition.armorPierce());
        if (!landed) {
            return;
        }
        // Knockback follows the strike, not just the geometry between the two bodies. See
        // AttackGeometry#knockbackAlignment for why the blend is a property of the shape.
        AttackDamage.knockback(victim, this.frame.origin(), this.frame.forward(),
                this.definition.knockback(), this.definition.knockbackVertical(),
                AttackGeometry.strikeDirection(this.definition, this.frame, progress()),
                AttackGeometry.knockbackAlignment(this.definition));

        if (!this.impactSoundPlayed) {
            this.impactSoundPlayed = true;
            this.definition.sounds().play(this.attacker.level(), victim.position(),
                    this.definition.sounds().impact(), this.attacker);
            this.definition.sounds().playWeight(this.attacker.level(), victim.position());
        }
        this.definition.hooks().fireHit(this, victim);
    }
}
