package com.cleannrooster.divineencounters.combat;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/// Optional per-attack callbacks, for the things that genuinely are specific to one move — a phase
/// transition firing off the back of a cleave, a lunge applying a status effect, a boss counting how many
/// thrusts it has landed.
///
/// Everything an attack normally needs (damage, knockback, geometry, sound, particles, the slash visual,
/// timing) is handled by the definition itself; these exist so the rare special case doesn't force a boss
/// back into writing bespoke attack code.
///
/// @param onStart       fired when the attack begins its windup
/// @param onActiveStart fired on the first damaging tick
/// @param onHit         fired once per victim actually damaged
/// @param onActiveEnd   fired when the damaging window closes
/// @param onFinish      fired when recovery ends and the attacker is free again
public record AttackHooks(
        @Nullable Consumer<AttackExecution> onStart,
        @Nullable Consumer<AttackExecution> onActiveStart,
        @Nullable BiConsumer<AttackExecution, LivingEntity> onHit,
        @Nullable Consumer<AttackExecution> onActiveEnd,
        @Nullable Consumer<AttackExecution> onFinish
) {
    public static final AttackHooks NONE = new AttackHooks(null, null, null, null, null);

    public static Builder builder() {
        return new Builder();
    }

    void fireStart(AttackExecution execution) {
        if (this.onStart != null) {
            this.onStart.accept(execution);
        }
    }

    void fireActiveStart(AttackExecution execution) {
        if (this.onActiveStart != null) {
            this.onActiveStart.accept(execution);
        }
    }

    void fireHit(AttackExecution execution, LivingEntity victim) {
        if (this.onHit != null) {
            this.onHit.accept(execution, victim);
        }
    }

    void fireActiveEnd(AttackExecution execution) {
        if (this.onActiveEnd != null) {
            this.onActiveEnd.accept(execution);
        }
    }

    void fireFinish(AttackExecution execution) {
        if (this.onFinish != null) {
            this.onFinish.accept(execution);
        }
    }

    public static final class Builder {
        private Consumer<AttackExecution> onStart;
        private Consumer<AttackExecution> onActiveStart;
        private BiConsumer<AttackExecution, LivingEntity> onHit;
        private Consumer<AttackExecution> onActiveEnd;
        private Consumer<AttackExecution> onFinish;

        public Builder onStart(Consumer<AttackExecution> hook) {
            this.onStart = hook;
            return this;
        }

        public Builder onActiveStart(Consumer<AttackExecution> hook) {
            this.onActiveStart = hook;
            return this;
        }

        public Builder onHit(BiConsumer<AttackExecution, LivingEntity> hook) {
            this.onHit = hook;
            return this;
        }

        public Builder onActiveEnd(Consumer<AttackExecution> hook) {
            this.onActiveEnd = hook;
            return this;
        }

        public Builder onFinish(Consumer<AttackExecution> hook) {
            this.onFinish = hook;
            return this;
        }

        public AttackHooks build() {
            return new AttackHooks(this.onStart, this.onActiveStart, this.onHit, this.onActiveEnd, this.onFinish);
        }
    }
}
