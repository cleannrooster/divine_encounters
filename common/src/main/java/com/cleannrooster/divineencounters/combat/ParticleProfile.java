package com.cleannrooster.divineencounters.combat;

import net.minecraft.core.particles.ParticleOptions;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/// How an attack scatters particles along its own geometry.
///
/// Particles are placed by sampling the attack surface through {@link AttackGeometry}, so they describe
/// the real damage volume by construction. The distribution along the blade comes from {@link #density};
/// the default profiles push weight toward the tip so a swing visibly gains energy outward, and no sample
/// is ever drawn past `t = 1`, which is the actual reach.
///
/// @param primary      the main particle spread across the swept surface
/// @param tipAccent    optional second particle restricted to the outer band, for a brighter leading edge
/// @param countPerTick particles emitted per active tick before the tick's swept fraction scales it
/// @param tipCount     tip-accent particles per active tick
/// @param density      distribution along the blade (root to tip)
/// @param tipBand      the outer fraction of the blade the tip accent is confined to (0.15 = outer 15%)
/// @param scatter      random positional jitter in blocks, so the surface reads as a volume
/// @param speed        outward velocity along the swing direction, in blocks/tick
/// @param drift        random velocity jitter in blocks/tick
/// @param edge         optional third layer riding the outer edge of the blade — the crackle of
///                     electricity, or the drift of released souls. Distinct from `tipAccent`
///                     because it is about *behaviour* rather than density: an edge accent can
///                     scatter violently or drift upward, which is what makes one read as
///                     lightning and the other as something dead.
/// @param sampler      the density curve integrated into a distribution, built once at profile creation
public record ParticleProfile(
        Supplier<ParticleOptions> primary,
        @Nullable Supplier<ParticleOptions> tipAccent,
        int countPerTick,
        int tipCount,
        DensityCurve density,
        float tipBand,
        float scatter,
        float speed,
        float drift,
        @Nullable EdgeAccent edge,
        DensitySampler sampler
) {
    /// A layer confined to the outer band of the blade, with its own motion.
    ///
    /// The two shapes it exists to produce:
    /// - **crackle** — short bolts of several particles along a random chord, thrown outward hard.
    ///   `bolt` above zero is what turns scattered points into something that reads as an arc of
    ///   electricity rather than sparks.
    /// - **drift** — single particles with almost no speed and a gentle upward lift, for souls
    ///   being pulled off a blade.
    ///
    /// @param particle the particle to emit
    /// @param count    emissions per active tick
    /// @param band     outer fraction of the blade it is confined to
    /// @param speed    outward speed along the swing
    /// @param chaos    random velocity added on every axis; high values crackle
    /// @param lift     constant upward velocity; positive values drift
    /// @param bolt     length in blocks of a mini-bolt, or 0 to emit single particles
    public record EdgeAccent(
            Supplier<ParticleOptions> particle,
            int count,
            float band,
            float speed,
            float chaos,
            float lift,
            float bolt
    ) {
        /// Violent, forked, thrown outward — electricity.
        public static EdgeAccent crackle(Supplier<ParticleOptions> particle, int count) {
            return new EdgeAccent(particle, count, 0.3f, 0.22f, 0.32f, 0.0f, 0.75f);
        }

        /// Slow, rising, barely moving outward — something leaving the body.
        public static EdgeAccent drift(Supplier<ParticleOptions> particle, int count) {
            return new EdgeAccent(particle, count, 0.42f, 0.02f, 0.03f, 0.045f, 0.0f);
        }
    }
    public static Builder builder(Supplier<ParticleOptions> primary) {
        return new Builder(primary);
    }

    public static final class Builder {
        private final Supplier<ParticleOptions> primary;
        private Supplier<ParticleOptions> tipAccent;
        private int countPerTick = 28;
        private int tipCount = 8;
        // Default: energy builds hard toward the outer edge, with a short fade right at maximum reach.
        private DensityCurve density = DensityCurve.leadingEdge(2.6f, 0.9f);
        private float tipBand = 0.2f;
        private float scatter = 0.12f;
        private float speed = 0.06f;
        private float drift = 0.02f;
        private EdgeAccent edge;

        private Builder(Supplier<ParticleOptions> primary) {
            this.primary = primary;
        }

        public Builder tipAccent(Supplier<ParticleOptions> accent, int count) {
            this.tipAccent = accent;
            this.tipCount = count;
            return this;
        }

        public Builder count(int countPerTick) {
            this.countPerTick = countPerTick;
            return this;
        }

        public Builder density(DensityCurve density) {
            this.density = density;
            return this;
        }

        public Builder tipBand(float fraction) {
            this.tipBand = fraction;
            return this;
        }

        public Builder scatter(float blocks) {
            this.scatter = blocks;
            return this;
        }

        public Builder speed(float blocksPerTick) {
            this.speed = blocksPerTick;
            return this;
        }

        public Builder drift(float blocksPerTick) {
            this.drift = blocksPerTick;
            return this;
        }

        /// Attach the outer-edge layer — the crackle or the drift.
        public Builder edge(EdgeAccent edge) {
            this.edge = edge;
            return this;
        }

        public ParticleProfile build() {
            return new ParticleProfile(this.primary, this.tipAccent, this.countPerTick, this.tipCount,
                    this.density, this.tipBand, this.scatter, this.speed, this.drift, this.edge,
                    new DensitySampler(this.density));
        }
    }
}
