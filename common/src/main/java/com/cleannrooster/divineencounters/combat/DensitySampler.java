package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.RandomSource;

/// Turns a {@link DensityCurve} into something you can draw `t` values from.
///
/// The curve is integrated once into a cumulative table over `[0, 1]`; drawing a sample is then a uniform
/// roll plus a binary search, so emitting a few hundred particles a tick costs nothing. Building the table
/// eagerly (rather than rejection-sampling per particle) also means an aggressively tip-loaded curve is no
/// more expensive than a flat one.
///
/// Samples are always inside `[0, 1]`, which is what guarantees particles stay within the attack's real
/// reach.
public final class DensitySampler {
    /// Table resolution. 64 bins resolves even a steep tip-loaded curve to well under a particle's own
    /// visual size at typical attack ranges.
    private static final int BINS = 64;

    private final float[] cumulative; // cumulative[i] = integral of the curve over [0, i/BINS]

    public DensitySampler(DensityCurve curve) {
        this.cumulative = new float[BINS + 1];
        var running = 0.0f;
        for (var i = 0; i < BINS; i++) {
            // Midpoint rule over each bin.
            var t = (i + 0.5f) / BINS;
            running += Math.max(0.0f, curve.weight(t));
            this.cumulative[i + 1] = running;
        }
        if (running <= 0.0f) {
            // Degenerate curve (all zero): fall back to uniform so an attack never renders as nothing.
            for (var i = 0; i <= BINS; i++) {
                this.cumulative[i] = (float) i / BINS;
            }
        }
    }

    /// Draw one `t` in `[0, 1]`, distributed according to the curve.
    public float sample(RandomSource random) {
        var total = this.cumulative[BINS];
        var target = random.nextFloat() * total;
        var lo = 0;
        var hi = BINS;
        while (lo < hi) {
            var mid = (lo + hi) >>> 1;
            if (this.cumulative[mid + 1] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // Interpolate inside the chosen bin so samples aren't visibly quantised.
        var binStart = this.cumulative[lo];
        var binWeight = this.cumulative[lo + 1] - binStart;
        var within = binWeight > 1.0e-6f ? (target - binStart) / binWeight : random.nextFloat();
        return Math.min(1.0f, (lo + within) / BINS);
    }
}
