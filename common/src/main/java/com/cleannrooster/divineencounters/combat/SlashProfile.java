package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.DivineEncounters;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/// The rendered blade shape for an attack: a textured ribbon laid directly onto the attack's own swept
/// surface, so what the player sees is the volume that hurts them.
///
/// The renderer that consumes this ({@code client.combat.SlashRenderer}) knows nothing about any specific
/// boss — it takes a profile plus an {@link AttackFrame} and draws the surface {@link AttackGeometry}
/// defines. Adding a new look means a new profile; adding a new *kind* of look means one more renderer
/// branch, not a rewrite of any AI.
///
/// @param texture       ribbon texture (a soft horizontal streak; U runs along the swing, V across the blade)
/// @param red           tint, 0-1
/// @param green         tint, 0-1
/// @param blue          tint, 0-1
/// @param alpha         peak opacity
/// @param sweepSegments subdivisions along the swing direction; more = smoother crescent
/// @param bladeSegments subdivisions from root to tip
/// @param trail         how much of the already-swept arc stays visible behind the leading edge, as a
///                      fraction of the full arc (0.35 = the last third of the swing is drawn)
/// @param innerFraction where the drawn ribbon starts along the blade, as a fraction of the range — a
///                      slash reads better when the innermost part near the shoulder is left empty
/// @param thickness     visual half-thickness perpendicular to the swing plane, in blocks
/// @param fadeOutTicks  how long the ribbon lingers and fades after the active window ends
/// @param emissive      draw fullbright, so the arc reads at night and indoors
/// @param overreach     how far a dim outer bloom projects past the real reach, as a fraction of the
///                      range. 0 draws nothing extra. See {@link #overreach()} — this is the one part
///                      of the ribbon that is deliberately not the damage volume, and it is drawn
///                      differently on purpose.
public record SlashProfile(
        ResourceLocation texture,
        float red,
        float green,
        float blue,
        float alpha,
        int sweepSegments,
        int bladeSegments,
        float trail,
        float innerFraction,
        float thickness,
        int fadeOutTicks,
        boolean emissive,
        float overreach
) {
    /// Pre-overreach profiles keep working unchanged.
    public SlashProfile(ResourceLocation texture, float red, float green, float blue, float alpha,
                        int sweepSegments, int bladeSegments, float trail, float innerFraction,
                        float thickness, int fadeOutTicks, boolean emissive) {
        this(texture, red, green, blue, alpha, sweepSegments, bladeSegments, trail, innerFraction,
                thickness, fadeOutTicks, emissive, 0.0f);
    }

    /// Hard ceiling on the bloom, as a fraction of the attack's range.
    ///
    /// The bloom exists so a strike reads as *projecting* force rather than stopping dead at the
    /// blade — but every block it extends is a block where the player sees an effect and takes no
    /// damage, and past a certain point that stops reading as pressure and starts reading as a lie.
    /// A third of the range is about where it turns.
    public static final float MAX_OVERREACH = 0.34f;

    /// Opacity of the bloom relative to the arc's own alpha.
    ///
    /// This number is the fairness contract, expressed as a constant. The bloom must be faint enough
    /// that the bright core of the ribbon — which *is* the damage volume, exactly — remains the
    /// obvious shape. If these two are ever comparable in brightness, the arc stops telling the
    /// truth about its reach.
    public static final float OVERREACH_ALPHA = 0.3f;

    public boolean hasOverreach() {
        return this.overreach > 0.001f && isVisible();
    }

    /// Default ribbon texture shipped with the mod.
    public static final ResourceLocation DEFAULT_TEXTURE =
            DivineEncounters.id("textures/effect/slash.png");
    /// Narrower, harder-edged texture for thrusts and charge trails.
    public static final ResourceLocation THRUST_TEXTURE =
            DivineEncounters.id("textures/effect/thrust.png");

    public static Builder builder() {
        return new Builder();
    }

    /// A profile that draws nothing — for attacks whose motion is the visual (shoves, body checks).
    public static final SlashProfile NONE =
            new SlashProfile(DEFAULT_TEXTURE, 1, 1, 1, 0.0f, 2, 2, 0.0f, 0.0f, 0.0f, 0, false);

    public boolean isVisible() {
        return this.alpha > 0.0f;
    }

    public static final class Builder {
        private ResourceLocation texture = DEFAULT_TEXTURE;
        private float red = 1.0f;
        private float green = 0.94f;
        private float blue = 0.72f;
        private float alpha = 0.85f;
        private int sweepSegments = 24;
        private int bladeSegments = 6;
        private float trail = 0.4f;
        private float innerFraction = 0.15f;
        private float overreach = 0.0f;
        private float thickness = 0.0f;
        private int fadeOutTicks = 4;
        private boolean emissive = true;

        public Builder texture(ResourceLocation texture) {
            this.texture = texture;
            return this;
        }

        public Builder color(float red, float green, float blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            return this;
        }

        public Builder alpha(float alpha) {
            this.alpha = alpha;
            return this;
        }

        public Builder segments(int sweepSegments, int bladeSegments) {
            this.sweepSegments = sweepSegments;
            this.bladeSegments = bladeSegments;
            return this;
        }

        public Builder trail(float trail) {
            this.trail = trail;
            return this;
        }

        public Builder innerFraction(float innerFraction) {
            this.innerFraction = innerFraction;
            return this;
        }

        public Builder thickness(float thickness) {
            this.thickness = thickness;
            return this;
        }

        public Builder fadeOutTicks(int ticks) {
            this.fadeOutTicks = ticks;
            return this;
        }

        public Builder emissive(boolean emissive) {
            this.emissive = emissive;
            return this;
        }

        /// Project a dim bloom this far past the attack's real reach, as a fraction of the range.
        ///
        /// Clamped to {@link SlashProfile#MAX_OVERREACH}. The bloom is drawn at a fraction of the
        /// arc's alpha and fades to nothing at its outer limit, so the solid part of the ribbon
        /// still marks exactly where the damage stops.
        public Builder overreach(float fraction) {
            this.overreach = Mth.clamp(fraction, 0.0f, MAX_OVERREACH);
            return this;
        }

        public SlashProfile build() {
            return new SlashProfile(this.texture, this.red, this.green, this.blue, this.alpha,
                    this.sweepSegments, this.bladeSegments, this.trail, this.innerFraction,
                    this.thickness, this.fadeOutTicks, this.emissive, this.overreach);
        }
    }
}
