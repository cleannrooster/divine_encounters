package com.cleannrooster.divineencounters.encounter.presence;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/// The evidence an unresolved entity leaves behind.
///
/// Two very different jobs, deliberately in one place so their intensities can be tuned against
/// each other:
///
/// - **Weak cues** are sprinkled across *several* candidates at once while nothing is resolved.
///   The point is ambiguity — "it could be at any of these" — not deception. They are sparse on
///   purpose: constant noise from every direction is the same as no information at all.
/// - **The strong cue** fires from exactly one position, immediately before manifesting there. It
///   is the player's warning, and it is the first half of the fairness contract. It must be
///   unmistakably directional and unmistakably different from the weak cues, or the player cannot
///   tell a real tell from ambient dread.
///
/// @param weakSound     quiet, ambiguous — hoof scrapes, rustling
/// @param strongSound   loud and directional — the commit
/// @param weakParticle  sparse dark motes at a candidate
/// @param strongParticle denser burst at the real position
/// @param weakChance    per-eligible-candidate chance per emission tick
/// @param weakInterval  ticks between weak-cue emissions
public record PresenceCues(
        @Nullable Supplier<SoundEvent> weakSound,
        @Nullable Supplier<SoundEvent> strongSound,
        @Nullable Supplier<ParticleOptions> weakParticle,
        @Nullable Supplier<ParticleOptions> strongParticle,
        float weakChance,
        int weakInterval
) {
    /// At most this many candidates emit in a single pass, however large the pool. Without a cap,
    /// a big arena would produce a wall of sound that carries no directional information.
    private static final int MAX_WEAK_EMITTERS = 3;

    public static PresenceCues none() {
        return new PresenceCues(null, null, null, null, 0.0f, 20);
    }

    /// Scatter weak evidence across a few eligible candidates. Called on an interval, not every
    /// tick.
    public void emitWeak(ServerLevel level, List<ManifestationCandidate> candidates,
                         RandomSource random) {
        if (candidates.isEmpty() || this.weakChance <= 0.0f) {
            return;
        }
        var emitted = 0;
        // Start at a random index so the same few candidates aren't always the ones heard from.
        var offset = random.nextInt(candidates.size());
        for (var i = 0; i < candidates.size() && emitted < MAX_WEAK_EMITTERS; i++) {
            var candidate = candidates.get((i + offset) % candidates.size());
            if (!candidate.valid() || candidate.visible()) {
                // Evidence from a spot the player can plainly see is empty is worse than none.
                continue;
            }
            if (random.nextFloat() > this.weakChance) {
                continue;
            }
            emitAt(level, candidate.cueOrigin(), this.weakSound, this.weakParticle, 0.35f, 1.15f, 3);
            emitted++;
        }
    }

    /// The commit. One position, loud, immediately before the entity becomes real there.
    public void emitStrong(ServerLevel level, Vec3 origin) {
        emitAt(level, origin, this.strongSound, this.strongParticle, 1.15f, 0.85f, 14);
    }

    private void emitAt(ServerLevel level, Vec3 origin, @Nullable Supplier<SoundEvent> sound,
                        @Nullable Supplier<ParticleOptions> particle, float volume, float pitch,
                        int count) {
        if (sound != null) {
            var event = sound.get();
            if (event != null) {
                level.playSound(null, origin.x, origin.y, origin.z, event, SoundSource.HOSTILE,
                        volume, pitch);
            }
        }
        if (particle != null) {
            var options = particle.get();
            if (options != null) {
                level.sendParticles(options, origin.x, origin.y, origin.z, count, 0.28, 0.4, 0.28, 0.01);
            }
        }
    }
}
