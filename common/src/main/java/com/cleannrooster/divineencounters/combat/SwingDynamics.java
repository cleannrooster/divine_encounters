package com.cleannrooster.divineencounters.combat;

import net.minecraft.util.Mth;

/// How a swing's *speed* is distributed across its active window.
///
/// Everything else in the attack system describes where a swing goes. This describes how fast it is
/// going when it gets there. A {@link SwingPath} declares its start and end angles; this decides
/// whether the blade covers that span at a constant rate or rips through the middle of it.
///
/// ### Why this is not just a visual tweak
///
/// The value returned here reshapes `s`, the swing-progress parameter, and `s` is the input to
/// {@link AttackGeometry#sweepAngleDegrees} — which backs the damage sweep, the rendered ribbon and
/// the particle distribution alike. One curve therefore moves all three together, and the drawn arc
/// stays exactly the volume that can hit you. There is no way to make the visual accelerate without
/// the hitbox accelerating with it, which is the property the whole pipeline is built to preserve.
///
/// The total duration is untouched: every curve here maps 0 to 0 and 1 to 1, so an attack declared
/// as N active ticks still sweeps its full arc in exactly N ticks. Only the distribution changes.
///
/// ### Why this improves animation sync rather than breaking it
///
/// Bedrock keyframes interpolate smoothly by default, so a hand-authored clip *already* eases out of
/// its wind-back pose and into its strike pose — the blade is genuinely slowest at the ends and
/// fastest in the middle. `STEADY` was the thing that disagreed with the authored motion, sweeping
/// the arc at a constant rate while the weapon accelerated. {@link #FORCEFUL} matches the clip's own
/// easing much more closely, which is why it can be applied to hand-authored animations without
/// touching them.
public enum SwingDynamics {
    /// Constant angular speed. The original behaviour, and still correct for anything that should
    /// read as mechanical or unhurried.
    STEADY {
        @Override
        public float shape(float s) {
            return s;
        }
    },

    /// Measured start, violent middle, controlled arrest.
    ///
    /// Smoothstep (`3s² − 2s³`) applied twice. A single smoothstep — or even smootherstep — leaves
    /// about 58% of the arc in the middle third, which is a noticeable improvement on linear's 33%
    /// but still reads as a brisk swing rather than a force event. Composing it gets to roughly 67%:
    /// the outer thirds of the window become a held anticipation and a clean settle, and two thirds
    /// of the blade's travel happens in the middle third of the time.
    ///
    /// Zero slope at both ends, symmetric about the midpoint, and polynomial throughout.
    ///
    /// This is War's signature. The damaging portion of the swing genuinely *is* the apex of its
    /// momentum, because the hit test reads the same curve the eye does.
    FORCEFUL {
        @Override
        public float shape(float s) {
            var once = s * s * (3.0f - 2.0f * s);
            return once * once * (3.0f - 2.0f * once);
        }
    },

    /// Pure acceleration, no settle: slowest at the root, fastest at full extension.
    ///
    /// For thrusts and lanes, where the point should arrive at its furthest reach still gaining
    /// speed. A lance that eases into its own maximum extension reads as *placing* the tip; one that
    /// is still accelerating when it gets there reads as launched.
    LUNGE {
        @Override
        public float shape(float s) {
            return s * s * (2.0f - s * 0.35f) / 1.65f;
        }
    };

    /// Remap swing progress. Implementations must satisfy `shape(0) == 0` and `shape(1) == 1` and be
    /// monotonically non-decreasing on `[0, 1]` — a curve that runs backwards would drag the damage
    /// sweep back over ground it already covered. `SwingDynamicsCheck` asserts all three.
    public abstract float shape(float s);

    /// Clamped convenience wrapper. Geometry calls this rather than {@link #shape} directly, so a
    /// caller that overshoots the window (a partial tick landing just past the end of the swing)
    /// cannot push the blade past its declared end angle.
    public final float apply(float s) {
        return shape(Mth.clamp(s, 0.0f, 1.0f));
    }
}
