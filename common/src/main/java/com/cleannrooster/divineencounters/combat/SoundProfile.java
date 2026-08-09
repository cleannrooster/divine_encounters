package com.cleannrooster.divineencounters.combat;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/// Sounds bound to an attack's lifecycle rather than sprinkled through a boss's AI. Any number of attack
/// definitions can share one profile, which is the point — "all my heavy cleaves sound like this" should
/// be one object, not a copy-pasted `playSound` in five places.
///
/// Every slot is optional. The entries are suppliers so profiles can be built as static constants while
/// the underlying registry entries are still deferred.
///
/// @param windup     played when the attack starts winding up (the telegraph cue)
/// @param swingStart played on the first active tick (the swing itself)
/// @param impact     played once, at the position of the first entity actually hit
/// @param recovery   played when the active window ends
/// @param volume     volume for all slots
/// @param pitch      base pitch for all slots
/// @param pitchJitter random pitch spread, +/- this amount, so repeated swings don't sound identical
/// @param weight      optional low layer played *under* the swing and the impact — see {@link #playWeight}
public record SoundProfile(
        @Nullable Supplier<SoundEvent> windup,
        @Nullable Supplier<SoundEvent> swingStart,
        @Nullable Supplier<SoundEvent> impact,
        @Nullable Supplier<SoundEvent> recovery,
        float volume,
        float pitch,
        float pitchJitter,
        @Nullable Supplier<SoundEvent> weight
) {
    /// Pre-weight profiles keep working unchanged.
    public SoundProfile(@Nullable Supplier<SoundEvent> windup, @Nullable Supplier<SoundEvent> swingStart,
                        @Nullable Supplier<SoundEvent> impact, @Nullable Supplier<SoundEvent> recovery,
                        float volume, float pitch, float pitchJitter) {
        this(windup, swingStart, impact, recovery, volume, pitch, pitchJitter, null);
    }

    /// How far the weight layer is pitched below the profile's own pitch, and how much louder it is.
    ///
    /// Force is carried by the bottom of the spectrum, and a swing sound alone lives in the top of
    /// it. Layering a deep, quiet hit underneath the bright one is the cheapest way to make a strike
    /// feel like it has mass — and because it occupies a different frequency range, it adds weight
    /// without competing with the sound that carries the *information*. That is the "clarity over
    /// loudness" requirement made concrete: the readable cue stays exactly as readable.
    public static final float WEIGHT_PITCH = 0.45f;
    public static final float WEIGHT_VOLUME = 1.25f;

    public static final SoundProfile SILENT = new SoundProfile(null, null, null, null, 1.0f, 1.0f, 0.0f);

    public static Builder builder() {
        return new Builder();
    }

    /// Play one slot at a world position. No-ops when the slot is empty, so callers never branch.
    public void play(Level level, Vec3 at, @Nullable Supplier<SoundEvent> slot, @Nullable Entity source) {
        if (slot == null || level.isClientSide) {
            return;
        }
        var event = slot.get();
        if (event == null) {
            return;
        }
        var jitter = this.pitchJitter <= 0.0f
                ? 0.0f
                : (level.getRandom().nextFloat() * 2.0f - 1.0f) * this.pitchJitter;
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE,
                this.volume, Math.max(0.1f, this.pitch + jitter));
    }

    /// Play the low layer, if this profile has one. Deliberately not pitch-jittered: the weight
    /// should sit in the same place every time, so it reads as the same force rather than as a
    /// separate sound effect.
    public void playWeight(Level level, Vec3 at) {
        if (this.weight == null || level.isClientSide) {
            return;
        }
        var event = this.weight.get();
        if (event == null) {
            return;
        }
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE,
                this.volume * WEIGHT_VOLUME, WEIGHT_PITCH);
    }

    public static final class Builder {
        private Supplier<SoundEvent> windup;
        private Supplier<SoundEvent> swingStart;
        private Supplier<SoundEvent> impact;
        private Supplier<SoundEvent> recovery;
        private float volume = 1.0f;
        private float pitch = 1.0f;
        private float pitchJitter = 0.08f;
        private Supplier<SoundEvent> weight;

        public Builder windup(Supplier<SoundEvent> sound) {
            this.windup = sound;
            return this;
        }

        public Builder swingStart(Supplier<SoundEvent> sound) {
            this.swingStart = sound;
            return this;
        }

        public Builder impact(Supplier<SoundEvent> sound) {
            this.impact = sound;
            return this;
        }

        public Builder recovery(Supplier<SoundEvent> sound) {
            this.recovery = sound;
            return this;
        }

        public Builder volume(float volume) {
            this.volume = volume;
            return this;
        }

        public Builder pitch(float pitch) {
            this.pitch = pitch;
            return this;
        }

        public Builder pitchJitter(float jitter) {
            this.pitchJitter = jitter;
            return this;
        }

        /// A deep layer played under the swing and the impact. See {@link SoundProfile#playWeight}.
        public Builder weight(Supplier<SoundEvent> sound) {
            this.weight = sound;
            return this;
        }

        public SoundProfile build() {
            return new SoundProfile(this.windup, this.swingStart, this.impact, this.recovery,
                    this.volume, this.pitch, this.pitchJitter, this.weight);
        }
    }
}
