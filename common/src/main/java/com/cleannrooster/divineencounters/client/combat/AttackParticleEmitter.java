package com.cleannrooster.divineencounters.client.combat;

import com.cleannrooster.divineencounters.combat.AttackGeometry;
import com.cleannrooster.divineencounters.combat.AttackShape;
import com.cleannrooster.divineencounters.combat.DirectionalAttack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/// Scatters an attack's particles across the slice of its own geometry swept this tick.
///
/// Two rules make the result legible:
///
/// 1. **Position comes from the attack, not from a hand-placed emitter.** Every particle is a point on
///    the surface {@link AttackGeometry} defines, so the cloud traces the real damage volume.
/// 2. **Density is weighted along the blade, never uniform.** The profile's
///    {@link com.cleannrooster.divineencounters.combat.DensityCurve} decides how the particle budget is
///    spread between the root and the tip; the stock curves put most of it near the outer edge, so a
///    swing visibly gains energy outward. Because the sampler only ever draws `t` in `[0, 1]`, and `t = 1`
///    is the attack's real reach, nothing can appear past the point where the attack stops hurting.
///
/// The emitted count also scales with the fraction of the swing covered this tick, so a slow sweep and a
/// fast one lay down comparable trails instead of the fast one looking starved.
public final class AttackParticleEmitter {
    private AttackParticleEmitter() {
    }

    static void emit(ClientLevel level, SlashEffect effect) {
        var definition = effect.definition();
        var profile = definition.particles();
        if (profile.countPerTick() <= 0) {
            return;
        }
        var random = level.random;
        var active = Math.max(1, definition.activeTicks());

        // The slice of the swing covered by this tick. Non-swept shapes resolve everywhere at once.
        float from;
        float to;
        if (definition.shape().isSwept()) {
            from = Mth.clamp((float) effect.age() / active, 0.0f, 1.0f);
            to = Mth.clamp((float) (effect.age() + 1) / active, 0.0f, 1.0f);
        } else {
            from = 0.0f;
            to = 1.0f;
        }

        var sampler = profile.sampler();
        var count = scaledCount(definition, profile.countPerTick(), to - from);
        for (var i = 0; i < count; i++) {
            var sample = AttackGeometry.sample(definition, effect.frame(), from, to, random, sampler);
            level.addParticle(profile.primary().get(),
                    sample.position().x, sample.position().y, sample.position().z,
                    sample.velocity().x, sample.velocity().y, sample.velocity().z);
        }

        emitTipAccent(level, effect, from, to);
        emitEdge(level, effect, from, to);
    }

    /// The outer-edge layer: crackling bolts for War, drifting souls for Malice.
    ///
    /// It stays inside the same `t <= 1` bound as everything else, so however violent the crackle
    /// looks it still cannot suggest reach the attack does not have. A bolt is drawn as a short
    /// chord *along* the blade rather than outward from it, for the same reason.
    private static void emitEdge(ClientLevel level, SlashEffect effect, float from, float to) {
        var definition = effect.definition();
        var edge = definition.particles().edge();
        if (edge == null || edge.count() <= 0) {
            return;
        }
        var random = level.random;
        var count = scaledCount(definition, edge.count(), to - from);
        var band = Mth.clamp(edge.band(), 0.01f, 1.0f);

        for (var i = 0; i < count; i++) {
            var s = Mth.lerp(random.nextFloat(), from, to);
            var t = 1.0f - random.nextFloat() * band;
            var outward = AttackGeometry.outwardDirection(definition, effect.frame(), s, t);

            if (edge.bolt() > 0.0f) {
                emitBolt(level, effect, edge, s, t, outward, random);
            } else {
                var position = AttackGeometry.surfacePoint(definition, effect.frame(), s, t);
                spawn(level, edge, position, outward, random);
            }
        }
    }

    /// A short fork of particles between two nearby points on the blade. Several points on a line
    /// is what separates "an arc of electricity" from "some sparks".
    private static void emitBolt(ClientLevel level, SlashEffect effect,
                                 com.cleannrooster.divineencounters.combat.ParticleProfile.EdgeAccent edge,
                                 float s, float t, net.minecraft.world.phys.Vec3 outward,
                                 net.minecraft.util.RandomSource random) {
        var definition = effect.definition();
        // Span a small slice of the swing, so the bolt lies along the edge rather than across it.
        var span = edge.bolt() * 0.06f;
        var s0 = Mth.clamp(s - span, 0.0f, 1.0f);
        var s1 = Mth.clamp(s + span, 0.0f, 1.0f);
        var steps = 3;
        for (var i = 0; i <= steps; i++) {
            var f = (float) i / steps;
            var bs = Mth.lerp(f, s0, s1);
            // Jitter each node off the blade so the fork looks struck rather than drawn.
            var bt = Mth.clamp(t + (random.nextFloat() - 0.5f) * 0.12f, 0.0f, 1.0f);
            var node = AttackGeometry.surfacePoint(definition, effect.frame(), bs, bt);
            spawn(level, edge, node, outward, random);
        }
    }

    private static void spawn(ClientLevel level,
                              com.cleannrooster.divineencounters.combat.ParticleProfile.EdgeAccent edge,
                              net.minecraft.world.phys.Vec3 position,
                              net.minecraft.world.phys.Vec3 outward,
                              net.minecraft.util.RandomSource random) {
        var velocity = outward.scale(edge.speed()).add(
                (random.nextDouble() * 2.0 - 1.0) * edge.chaos(),
                (random.nextDouble() * 2.0 - 1.0) * edge.chaos() + edge.lift(),
                (random.nextDouble() * 2.0 - 1.0) * edge.chaos());
        level.addParticle(edge.particle().get(), position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z);
    }

    /// A second, heavier particle confined to the outer band of the blade. This is what actually sells
    /// "the energy is at the tip" — the primary cloud describes the shape, the accent marks the edge that
    /// reaches furthest.
    private static void emitTipAccent(ClientLevel level, SlashEffect effect, float from, float to) {
        var definition = effect.definition();
        var profile = definition.particles();
        var accent = profile.tipAccent();
        if (accent == null || profile.tipCount() <= 0) {
            return;
        }
        var random = level.random;
        var count = scaledCount(definition, profile.tipCount(), to - from);
        var band = Mth.clamp(profile.tipBand(), 0.01f, 1.0f);
        for (var i = 0; i < count; i++) {
            var s = Mth.lerp(random.nextFloat(), from, to);
            // Uniform inside the outer band only, so the accent never drifts down the blade.
            var t = 1.0f - random.nextFloat() * band;
            var position = AttackGeometry.surfacePoint(definition, effect.frame(), s, t);
            var outward = AttackGeometry.outwardDirection(definition, effect.frame(), s, t)
                    .scale(profile.speed() * 1.5f);
            level.addParticle(accent.get(),
                    position.x + jitter(random.nextFloat(), profile.scatter()),
                    position.y + jitter(random.nextFloat(), profile.scatter()),
                    position.z + jitter(random.nextFloat(), profile.scatter()),
                    outward.x, outward.y, outward.z);
        }
    }

    /// Budget for this tick. A swept attack only pays for the arc it actually covered; an instantaneous
    /// one (cone, impact) spends its whole budget on the single tick it exists for.
    private static int scaledCount(DirectionalAttack definition, int budget, float sweptFraction) {
        if (!definition.shape().isSwept()) {
            return budget;
        }
        if (definition.shape().family() == AttackShape.Family.PATH) {
            return budget; // a charge re-emits its whole trail every tick as it travels
        }
        return Math.max(1, Math.round(budget * Mth.clamp(sweptFraction, 0.0f, 1.0f) * definition.activeTicks()));
    }

    private static double jitter(float roll, float amount) {
        return (roll * 2.0f - 1.0f) * amount;
    }
}
