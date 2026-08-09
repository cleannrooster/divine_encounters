package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/// Vertical aiming for attacks: how far up or down a swing tilts toward its target.
///
/// ### Why this exists rather than just using the attacker's pitch
///
/// It used to. {@link AttackGeometry#capture} read `attacker.getXRot()`, and the bosses' controllers
/// dutifully set it every tick — but vanilla throws that away. `LookControl.tick()` begins with
///
/// ```
/// if (resetXRotOnTick()) this.mob.setXRot(0.0F);
/// ```
///
/// `resetXRotOnTick()` returns true by default, and `Mob.serverAiStep()` runs `lookControl.tick()`
/// *after* `customServerAiStep()` — where all boss logic lives. So the aim was computed, stored, and
/// erased before anything could read it, every tick, and every attack committed with a pitch of
/// zero. The visible symptom is attacks behaving as though the player were always exactly level.
///
/// Rather than fight the look control for ownership of a field it resets by design, attacks now
/// carry their own aim. {@link Aiming#attackPitch()} is read at capture time, is never touched by
/// vanilla, and is independent of wherever the entity's head happens to be pointing — which also
/// lets a boss aim its attacks without tilting its body, something the Visage of Malice specifically
/// wants.
public final class AttackAim {
    /// Hard cap, up and down. An attack tilted further than this stops reading as a swing at an
    /// angle and starts reading as a swing at the floor or the sky.
    public static final float MAX_PITCH = 45.0f;

    private AttackAim() {
    }

    /// A boss whose attacks aim vertically. Implemented by the entity, updated by its controller.
    public interface Aiming {
        /// Pitch to apply to committed attacks, in Minecraft's convention: negative is up, positive
        /// is down. Already curved and clamped — see {@link #curve}.
        float attackPitch();
    }

    /// The true elevation angle from `attacker` to `target`, in degrees, MC convention.
    ///
    /// Measured centre-of-mass to centre-of-mass rather than eye to feet, because a swing is thrown
    /// by the whole body and aiming a halberd at a player's shoes looks like a miss even when it
    /// connects.
    public static float rawPitchToward(LivingEntity attacker, Entity target) {
        var dx = target.getX() - attacker.getX();
        var dz = target.getZ() - attacker.getZ();
        var dy = (target.getY() + target.getBbHeight() * 0.5)
                - (attacker.getY() + attacker.getBbHeight() * 0.5);
        var horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0e-4 && Math.abs(dy) < 1.0e-4) {
            return 0.0f;
        }
        return (float) -(Mth.atan2(dy, horizontal) * (180.0 / Math.PI));
    }

    /// The curved, capped pitch an attack should commit at.
    public static float pitchToward(LivingEntity attacker, Entity target) {
        return curve(rawPitchToward(attacker, target));
    }

    /// Compress a true elevation angle into the usable range.
    ///
    /// `MAX_PITCH * tanh(raw / MAX_PITCH)`. Three properties make this the right shape:
    ///
    /// - **Unit slope at zero.** For a target within a few degrees of level — which is most of any
    ///   fight — the aim is essentially exact. A curve that compressed near the origin would trade
    ///   away accuracy in the common case to solve a rare one.
    /// - **Progressive compression.** A target 45° above tilts the attack 34°, one 90° above tilts
    ///   it 43°. Steep geometry still reads as steep without the swing going fully vertical, which
    ///   on a horizontal arc would collapse the arc's whole lateral spread into a line and make a
    ///   wide cleave much harder to read than it should be.
    /// - **Asymptotic, not clipped.** It approaches the cap without ever reaching it, so there is no
    ///   angle at which the aim visibly stops responding — a hard clamp produces a dead zone where
    ///   climbing higher changes nothing, and the player feels it.
    ///
    /// Odd-symmetric, so aiming up and aiming down behave identically.
    public static float curve(float rawDegrees) {
        var compressed = MAX_PITCH * (float) Math.tanh(rawDegrees / MAX_PITCH);
        return Mth.clamp(compressed, -MAX_PITCH, MAX_PITCH);
    }

    /// Whether an attack's geometry should be tilted by the attacker's aim at all.
    ///
    /// Radial impacts are excluded. A shockwave is a disc on the ground with no direction but
    /// outward, and tilting it would lift half the ring into the air and bury the other half —
    /// turning a readable ground slam into something that misses in a way the player cannot see.
    public static boolean appliesTo(DirectionalAttack definition) {
        return definition.shape().family() != AttackShape.Family.RADIAL;
    }

    /// Ease the stored aim toward a new target angle.
    ///
    /// Attacks capture this the instant they commit, so letting it jump would make a boss snap its
    /// aim onto a player who hopped up a block. Easing means the aim lags slightly, which is both
    /// more readable and more honest: the attack is thrown where the target *was* when the swing
    /// started, which is exactly the contract the rest of the attack system already keeps.
    public static float ease(float current, float desired, float rate) {
        return current + (desired - current) * Mth.clamp(rate, 0.0f, 1.0f);
    }
}
