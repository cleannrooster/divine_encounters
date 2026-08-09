package com.cleannrooster.divineencounters.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Predicate;

/// Per-entity handle on the attack system. One runner per attacking entity, ticked once per server tick.
///
/// A runner enforces the rule that matters most for readable boss fights: **one attack at a time**. Goals
/// or state machines ask `start(...)`, and are simply refused while something is already running, so two
/// independent behaviours can never overlap their damage windows.
///
/// Typical use from an entity:
/// ```java
/// if (!this.attacks.isBusy() && shouldSwing()) {
///     this.attacks.start(DivineAttacks.WING_SWEEP);
/// }
/// this.attacks.tick();
/// ```
public final class AttackRunner {
    private final LivingEntity attacker;
    private Predicate<LivingEntity> targetFilter;
    private @Nullable Consumer<String> animator;
    private @Nullable Consumer<Vec3> motionSink;

    private @Nullable AttackExecution current;

    public AttackRunner(LivingEntity attacker) {
        this.attacker = attacker;
        // Sensible default: a boss doesn't cleave its own kind.
        this.targetFilter = candidate -> candidate.getType() != attacker.getType();
    }

    /// Extra restriction on what an attack may damage, on top of the built-in self/ally/spectator checks.
    public AttackRunner targetFilter(Predicate<LivingEntity> filter) {
        this.targetFilter = filter;
        return this;
    }

    /// Where an attack's animation name is sent when it starts. Keeps the combat system free of any
    /// dependency on a particular animation library.
    public AttackRunner animator(Consumer<String> animator) {
        this.animator = animator;
        return this;
    }

    /// Where the attack's per-tick motion contribution goes. Entities with their own steering (a hovering
    /// boss, say) should route it into their controller; leave it unset and it is added to the entity's
    /// velocity directly.
    public AttackRunner motionSink(Consumer<Vec3> sink) {
        this.motionSink = sink;
        return this;
    }

    // --- state -----------------------------------------------------------------------------------

    public boolean isBusy() {
        return this.current != null;
    }

    public @Nullable AttackExecution current() {
        return this.current;
    }

    public @Nullable DirectionalAttack currentDefinition() {
        return this.current == null ? null : this.current.definition();
    }

    public AttackPhase phase() {
        return this.current == null ? AttackPhase.FINISHED : this.current.phase();
    }

    /// True while an attack is committed — the AI should not be steering freely.
    public boolean isCommitted() {
        return this.current != null && this.current.phase() != AttackPhase.FINISHED;
    }

    /// Turn cap the attacker should respect this tick. {@link TrackingMode#FULL} when idle.
    public TrackingMode tracking() {
        return this.current == null ? TrackingMode.FULL : this.current.tracking();
    }

    // --- driving ---------------------------------------------------------------------------------

    /// Begin an attack. Refused (returns false) while another is running, which is what keeps two goals
    /// from starting overlapping damage windows.
    public boolean start(DirectionalAttack definition) {
        return start(definition, 0.0f);
    }

    /// As {@link #start(DirectionalAttack)}, with an extra roll applied to the swing plane.
    public boolean start(DirectionalAttack definition, float rollOffset) {
        return start(definition, rollOffset, false);
    }

    /// Full form. `mirrored` plays the swing from the attacker's other side, and should only be passed
    /// when there is a mirrored animation to match it.
    ///
    /// Swing direction is deliberately never randomised here: the definition's {@link SwingPath} states
    /// which way the blade travels, so the rendered arc always agrees with the clip that is playing.
    public boolean start(DirectionalAttack definition, float rollOffset, boolean mirrored) {
        if (this.current != null) {
            return false;
        }
        var frame = AttackGeometry.capture(definition, this.attacker, mirrored, rollOffset);
        var execution = new AttackExecution(definition, this.attacker, frame, this.targetFilter);
        this.current = execution;
        if (this.animator != null) {
            this.animator.accept(definition.animation());
        }
        execution.begin();
        return true;
    }

    /// Begin an attack at an explicit world frame instead of at the attacker.
    ///
    /// Almost every attack radiates from whoever swung it, which is why {@link #start} captures the
    /// frame from the attacker. A delayed strike on a remembered position does not: the attacker
    /// has moved on, and the attack has to resolve where the mark was left. Since {@link AttackFrame}
    /// already carries an absolute origin, honouring that is just a matter of not overwriting it.
    ///
    /// The attacker is still the damage source and still owns the execution, so hooks, sounds and
    /// the client visual all behave normally.
    public boolean startAt(DirectionalAttack definition, AttackFrame frame) {
        if (this.current != null) {
            return false;
        }
        var execution = new AttackExecution(definition, this.attacker, frame, this.targetFilter);
        this.current = execution;
        if (this.animator != null) {
            this.animator.accept(definition.animation());
        }
        execution.begin();
        return true;
    }

    /// Abandon whatever is running — a stagger, a death, a phase transition cutting a swing short.
    public void cancel() {
        this.current = null;
    }

    /// Advance the active execution. Call once per server tick, before the entity's own movement code so
    /// the motion contribution lands in the same tick.
    public void tick() {
        var execution = this.current;
        if (execution == null) {
            return;
        }
        var motion = execution.motionThisTick();
        if (motion.lengthSqr() > 1.0e-9) {
            if (this.motionSink != null) {
                this.motionSink.accept(motion);
            } else {
                this.attacker.setDeltaMovement(this.attacker.getDeltaMovement().add(motion));
            }
        }
        if (!execution.tick()) {
            this.current = null;
        }
    }
}
