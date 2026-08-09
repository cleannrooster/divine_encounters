package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.Mth;

/// Relative particle density along an attack, as a function of normalised distance from the attack's root
/// to its tip (`t` in `[0, 1]`, the same `t` {@link AttackGeometry} uses).
///
/// The curve returns an unnormalised weight; {@link DensitySampler} turns it into a distribution to draw
/// particle positions from. Because the curve is only ever evaluated on `[0, 1]` and the sampler never
/// draws outside that range, particles physically cannot spawn past the real damage limit — the taper at
/// the edge of the AoE is a property of the system, not something each attack has to remember to do.
///
/// Implement this directly (it is a functional interface) for a distribution the factories below don't
/// cover; nothing else in the attack pipeline needs to change.
@FunctionalInterface
public interface DensityCurve {
    /// Unnormalised density at `t`, where 0 is the attack's root/origin and 1 is its outermost damaging
    /// point. Must be finite and non-negative.
    float weight(float t);

    /// Even spread from root to tip. Rarely what you want for a slash — included as a baseline.
    static DensityCurve uniform() {
        return t -> 1.0f;
    }

    /// Sparse at the root, densest at the tip. `exponent` controls how hard the weight is pushed outward:
    /// 1 is a straight ramp, 2-3 reads as a swing gaining energy, 5+ concentrates almost everything in the
    /// outer fifth. `root` is the residual weight at `t = 0`, which keeps the inner part of the swing
    /// faintly visible rather than empty.
    static DensityCurve tipLoaded(float exponent, float root) {
        return t -> root + (1.0f - root) * (float) Math.pow(Mth.clamp(t, 0.0f, 1.0f), exponent);
    }

    /// Convenience: tip-loaded with a small residual root density.
    static DensityCurve tipLoaded(float exponent) {
        return tipLoaded(exponent, 0.08f);
    }

    /// Densest at the root and thinning outward — a shove or a pressure burst rather than a blade.
    static DensityCurve rootLoaded(float exponent, float tip) {
        return t -> tip + (1.0f - tip) * (float) Math.pow(1.0f - Mth.clamp(t, 0.0f, 1.0f), exponent);
    }

    /// Tip-loaded, but with the peak pulled slightly inside the tip and a short fade over the last stretch
    /// of the blade. Reads as a leading edge with the energy just behind it, and softens the boundary at
    /// maximum reach without letting anything spill past it.
    ///
    /// @param exponent how hard density builds toward the peak
    /// @param peak     where the maximum sits, as a fraction of the range (0.85-0.95 works well)
    static DensityCurve leadingEdge(float exponent, float peak) {
        float p = Mth.clamp(peak, 0.05f, 0.999f);
        return t -> {
            float c = Mth.clamp(t, 0.0f, 1.0f);
            if (c <= p) {
                return 0.06f + 0.94f * (float) Math.pow(c / p, exponent);
            }
            // Past the peak the weight drops off toward the hard limit at t = 1.
            float over = (c - p) / (1.0f - p);
            return 1.0f - 0.75f * over * over;
        };
    }

    /// A band around the middle of the blade — useful for pressure waves and cone effects where the
    /// energy is not concentrated at a cutting edge.
    static DensityCurve centered(float sharpness) {
        return t -> {
            float d = Mth.clamp(t, 0.0f, 1.0f) - 0.5f;
            return (float) Math.exp(-sharpness * d * d);
        };
    }

    /// Multiply two curves — e.g. a tip-loaded blade profile modulated by an edge fade.
    default DensityCurve times(DensityCurve other) {
        return t -> this.weight(t) * other.weight(t);
    }
}
