package com.cleannrooster.divineencounters.combat;

import com.cleannrooster.divineencounters.particle.TintedParticleOptions;
import com.cleannrooster.divineencounters.registry.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

import java.util.function.Supplier;

/// Ready-made particle and slash profiles, so a new attack normally picks a look off the shelf instead of
/// hand-tuning one. All of them keep the density weighted toward the tip — that is the house style, and
/// it is also what makes an attack's reach legible.
///
/// ### On making arcs bigger
/// The ribbons are drawn large, and every one of them is still bounded by the attack's real damage
/// volume. The room to grow came from the fact that they were previously drawn *smaller* than the
/// thing they represent: a crescent starting 20% out along the blade and showing 45% of the swept
/// arc was describing less than half the volume that could actually hit you. Pushing
/// `innerFraction` toward zero, `trail` toward one, and giving the ribbon real thickness fills the
/// true shape instead of exceeding it — so the arcs got substantially larger *and* more honest at
/// the same time.
///
/// The hard limit: `thickness` is clamped by the renderer to the attack's own half-extent, so no
/// amount of visual bulk can put the blade somewhere the damage is not.
///
/// ### On the overreach bloom
/// War's cleaves now project a dim bloom past their real tip. This is the single deliberate
/// exception to "the drawn arc is the damage volume", and it is drawn so it cannot be mistaken for
/// one: 30% of the arc's alpha, squared falloff to nothing, no thickness, and rendered *under* the
/// solid ribbon. The bright edge of the arc is still exactly where damage stops, and it is still the
/// brightest thing on screen.
///
/// The reason to accept the exception is that a strike which stops dead at the blade reads as the
/// blade's reach; one that bleeds a little past it reads as force being *projected* along the strike,
/// which is the whole point of this boss. Malice's profiles deliberately do not use it — its arcs
/// should feel like they take something away, not like they push.
///
/// ### On tip electricity
/// Every War profile ends in an {@code EdgeAccent.crackle}, which throws short bolts confined to the
/// outer `tipBand` of the blade — the same band where the density curve piles its sparks. The bolts
/// are the loudest part of the effect and they sit at the furthest-reaching part of the arc, so
/// "the outer edge carries the most energy" is literally how the particles are distributed rather
/// than something the art has to imply.
public final class AttackVisuals {
    private AttackVisuals() {
    }

    // --- palette ---------------------------------------------------------------------------------

    private static final float[] GOLD = {1.0f, 0.90f, 0.55f};
    private static final float[] PALE_GOLD = {1.0f, 0.97f, 0.82f};

    public static Supplier<ParticleOptions> spark(float[] colour, float scale) {
        return () -> new TintedParticleOptions(ModParticles.DIVINE_SPARK.get(),
                colour[0], colour[1], colour[2], scale);
    }

    public static Supplier<ParticleOptions> mote(float[] colour, float scale) {
        return () -> new TintedParticleOptions(ModParticles.DIVINE_MOTE.get(),
                colour[0], colour[1], colour[2], scale);
    }

    // --- particle profiles -----------------------------------------------------------------------

    /// The default used by any attack that doesn't specify one: sparse at the shoulder, dense at the
    /// outer edge, nothing past the real reach.
    public static ParticleProfile defaultParticles() {
        return ParticleProfile.builder(spark(GOLD, 0.22f))
                .count(38)
                .density(DensityCurve.leadingEdge(2.6f, 0.9f))
                .tipAccent(mote(PALE_GOLD, 0.30f), 10)
                .tipBand(0.2f)
                .scatter(0.13f)
                .speed(0.05f)
                .edge(ParticleProfile.EdgeAccent.crackle(() -> ParticleTypes.ELECTRIC_SPARK, 5))
                .build();
    }

    /// For sweeps and cleaves: a steeper build so the outer third of the arc carries most of the
    /// energy. War's signature: gold along the blade with electricity crackling off the outer edge. The
    /// bolts are confined to the tip band, which is also where the density curve piles the sparks,
    /// so the loudest part of the effect is exactly the part that reaches furthest.
    public static ParticleProfile sweepParticles(int count, float exponent) {
        return ParticleProfile.builder(spark(GOLD, 0.23f))
                .count((int) (count * 1.8f))
                .density(DensityCurve.leadingEdge(exponent, 0.9f))
                .tipAccent(mote(PALE_GOLD, 0.34f), Math.max(8, count / 2))
                // Tightened from 0.22. A narrower band is what turns "sparkly along the blade" into
                // "the last fifth of this thing is where the energy is".
                .tipBand(0.16f)
                .scatter(0.15f)
                .speed(0.07f)
                // Roughly tripled, and confined to that narrower band. This is the crackle the pass
                // asks for: violent at the outer edge, absent nearer the shoulder, and never dense
                // enough to bury the crescent it is riding.
                .edge(ParticleProfile.EdgeAccent.crackle(() -> ParticleTypes.ELECTRIC_SPARK,
                        Math.max(10, count / 2)))
                .build();
    }

    /// For thrusts: the body of the lane stays visible, the leading point is where the mass sits, and a
    /// hard cutoff at full extension marks exactly how far the spear reaches.
    public static ParticleProfile thrustParticles(int count) {
        return ParticleProfile.builder(spark(PALE_GOLD, 0.14f))
                .count(count)
                // A higher floor than a slash: a thrust should read as a continuous lane, not a dot.
                .density(DensityCurve.tipLoaded(3.4f, 0.22f))
                .tipAccent(mote(GOLD, 0.38f), Math.max(10, count / 2))
                .tipBand(0.11f)
                .scatter(0.09f)
                .speed(0.16f)
                // A lance concentrates everything at one point rather than along an edge, so its
                // crackle is tighter and denser than a sweep's despite the lower count.
                .edge(ParticleProfile.EdgeAccent.crackle(() -> ParticleTypes.ELECTRIC_SPARK,
                        Math.max(7, count / 3)))
                .build();
    }

    /// For a charge: the mass rides the advancing front and the trail behind dissipates quickly.
    public static ParticleProfile chargeParticles(int count) {
        return ParticleProfile.builder(spark(PALE_GOLD, 0.2f))
                .count(count)
                .density(DensityCurve.tipLoaded(3.0f, 0.10f))
                .tipAccent(mote(GOLD, 0.34f), Math.max(6, count / 3))
                .tipBand(0.18f)
                .scatter(0.18f)
                .speed(0.04f)
                .drift(0.04f)
                // The advancing front of a charge is the whole attack, so it gets the heaviest
                // crackle of any profile here.
                .edge(ParticleProfile.EdgeAccent.crackle(() -> ParticleTypes.ELECTRIC_SPARK, 12))
                .build();
    }

    /// For radial impacts, where there is no blade — energy sits at the outer edge of the shockwave.
    public static ParticleProfile impactParticles(int count) {
        return ParticleProfile.builder(spark(GOLD, 0.24f))
                .count((int) (count * 1.4f))
                .density(DensityCurve.tipLoaded(2.0f, 0.15f))
                .tipAccent(mote(PALE_GOLD, 0.38f), Math.max(8, count / 2))
                .scatter(0.24f)
                .speed(0.1f)
                .edge(ParticleProfile.EdgeAccent.crackle(() -> ParticleTypes.ELECTRIC_SPARK, 14))
                .build();
    }

    // --- slash profiles --------------------------------------------------------------------------

    public static SlashProfile crescent(float alpha, int sweepSegments) {
        return SlashProfile.builder()
                .color(GOLD[0], GOLD[1], GOLD[2])
                .alpha(Math.min(1.0f, alpha + 0.08f))
                .segments((int) (sweepSegments * 1.5f), 8)
                // Nearly the whole swept arc, and nearly the whole blade radius: this is the volume
                // that can actually hit you, so this is what gets drawn.
                .trail(0.9f)
                .innerFraction(0.06f)
                .thickness(0.22f)
                .fadeOutTicks(6)
                // A modest bleed past the tip on ordinary cuts — enough to suggest displacement, not
                // enough to argue with the hitbox.
                .overreach(0.16f)
                .build();
    }

    public static SlashProfile heavyCrescent() {
        return SlashProfile.builder()
                .color(PALE_GOLD[0], PALE_GOLD[1], PALE_GOLD[2])
                .alpha(1.0f)
                .segments(48, 10)
                .trail(1.0f)
                .innerFraction(0.03f)
                .thickness(0.36f)
                // Heavy cleaves get the full allowance. This is the attack that should look like it
                // moved the air a metre past where the blade stopped.
                .fadeOutTicks(11)
                .overreach(SlashProfile.MAX_OVERREACH)
                .build();
    }

    public static SlashProfile thrustStreak() {
        return SlashProfile.builder()
                .texture(SlashProfile.THRUST_TEXTURE)
                .color(PALE_GOLD[0], PALE_GOLD[1], PALE_GOLD[2])
                .alpha(1.0f)
                .segments(18, 12)
                // A thrust has no arc to trail behind; the whole extended lane is the visual.
                .trail(1.0f)
                .innerFraction(0.0f)
                .thickness(0.2f)
                .fadeOutTicks(5)
                .build();
    }

    // --- Malice palette ---------------------------------------------------------------------------

    /// Malice's arcs are the visual inverse of War's: cold violet-black instead of bright gold, and
    /// substantially dimmer. They still have to read as a telegraph in a dark arena, so the tip
    /// stays bright while the body of the blade falls away — the legibility comes from the leading
    /// edge rather than from overall brightness.
    private static final float[] SPITE = {0.62f, 0.32f, 0.78f};
    private static final float[] PALE_SPITE = {0.86f, 0.72f, 0.95f};

    /// Malice's signature: cold violet along the blade, with souls pulling free of the outer edge
    /// and drifting upward. The inverse of War's crackle in every way that matters — slow instead of
    /// violent, rising instead of thrown — while occupying exactly the same band of the blade.
    public static ParticleProfile spiteParticles(int count, float exponent) {
        return ParticleProfile.builder(spark(SPITE, 0.21f))
                .count((int) (count * 1.5f))
                .density(DensityCurve.leadingEdge(exponent, 0.92f))
                .tipAccent(mote(PALE_SPITE, 0.32f), Math.max(6, count / 3))
                .tipBand(0.18f)
                .scatter(0.16f)
                .speed(0.06f)
                .edge(ParticleProfile.EdgeAccent.drift(() -> ParticleTypes.SOUL,
                        Math.max(3, count / 8)))
                .build();
    }

    /// A thrust's edge carries witch-magic rather than souls: it is a puncture, not a harvest, and
    /// the swirl reads as something being channelled down the lance.
    public static ParticleProfile spiteThrustParticles(int count) {
        return ParticleProfile.builder(spark(PALE_SPITE, 0.17f))
                .count((int) (count * 1.5f))
                .density(DensityCurve.tipLoaded(3.6f, 0.2f))
                .tipAccent(mote(SPITE, 0.36f), Math.max(8, count / 2))
                .tipBand(0.12f)
                .scatter(0.08f)
                .speed(0.17f)
                .edge(ParticleProfile.EdgeAccent.drift(() -> ParticleTypes.WITCH,
                        Math.max(3, count / 7)))
                .build();
    }

    /// Dim crescent for Malice's sweeps. Lower alpha than War's so it reads as a shadow of a blade.
    public static SlashProfile spiteCrescent(float alpha, int sweepSegments) {
        return SlashProfile.builder()
                .color(SPITE[0], SPITE[1], SPITE[2])
                // Still dimmer than War's gold — Malice's arcs are large but never bright, or they
                // would light the arena its whole fight depends on keeping dark.
                .alpha(Math.min(0.92f, alpha + 0.1f))
                .segments((int) (sweepSegments * 1.5f), 8)
                .trail(0.92f)
                .innerFraction(0.05f)
                .thickness(0.24f)
                .fadeOutTicks(7)
                .build();
    }

    public static SlashProfile spiteWideCrescent() {
        return SlashProfile.builder()
                .color(PALE_SPITE[0], PALE_SPITE[1], PALE_SPITE[2])
                .alpha(0.94f)
                .segments(50, 10)
                .trail(1.0f)
                .innerFraction(0.03f)
                .thickness(0.38f)
                .fadeOutTicks(10)
                .build();
    }

    public static SlashProfile spiteThrust() {
        return SlashProfile.builder()
                .texture(SlashProfile.THRUST_TEXTURE)
                .color(PALE_SPITE[0], PALE_SPITE[1], PALE_SPITE[2])
                .alpha(0.92f)
                .segments(18, 12)
                .trail(1.0f)
                .innerFraction(0.0f)
                .thickness(0.2f)
                .fadeOutTicks(5)
                .build();
    }

    public static SlashProfile chargeTrail() {
        return SlashProfile.builder()
                .texture(SlashProfile.THRUST_TEXTURE)
                .color(PALE_GOLD[0], PALE_GOLD[1], PALE_GOLD[2])
                .alpha(0.88f)
                .segments(16, 9)
                .trail(1.0f)
                .innerFraction(0.0f)
                .thickness(0.26f)
                .fadeOutTicks(4)
                .build();
    }
}
