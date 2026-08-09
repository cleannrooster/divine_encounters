package com.cleannrooster.divineencounters.client.particle;

import com.cleannrooster.divineencounters.particle.TintedParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;

/// The camera-facing spark every directional attack scatters along its geometry. Colour and size ride in
/// the options, so one registered particle serves every attack profile in the mod.
///
/// It shrinks and fades over its (short) life, which is what makes a swing read as a moving edge rather
/// than a cloud that lingers where the attack used to be.
public class DivineSparkParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float baseSize;

    DivineSparkParticle(ClientLevel level, double x, double y, double z,
                        double dx, double dy, double dz,
                        TintedParticleOptions options, SpriteSet sprites, int lifetime, float drag) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.rCol = options.red();
        this.gCol = options.green();
        this.bCol = options.blue();
        this.baseSize = options.scale();
        this.quadSize = options.scale();
        this.lifetime = lifetime + level.random.nextInt(4);
        this.gravity = 0.0f;
        this.hasPhysics = false;
        this.friction = drag;
        this.xd = dx;
        this.yd = dy;
        this.zd = dz;
        this.setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.age < this.lifetime) {
            this.setSpriteFromAge(this.sprites);
        }
        // Shrink and fade together so the trail thins out behind the moving edge of the swing.
        var remaining = 1.0f - (float) this.age / this.lifetime;
        this.quadSize = this.baseSize * Mth.clamp(remaining, 0.0f, 1.0f);
        this.alpha = Mth.clamp(remaining * 1.4f, 0.0f, 1.0f);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /// Small, fast spark — the body of a slash.
    public static class SparkProvider implements ParticleProvider<TintedParticleOptions> {
        private final SpriteSet sprites;

        public SparkProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(TintedParticleOptions options, ClientLevel level,
                                       double x, double y, double z, double dx, double dy, double dz) {
            return new DivineSparkParticle(level, x, y, z, dx, dy, dz, options, this.sprites, 6, 0.86f);
        }
    }

    /// Heavier, slower mote — the leading edge accent and impact bursts.
    public static class MoteProvider implements ParticleProvider<TintedParticleOptions> {
        private final SpriteSet sprites;

        public MoteProvider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(TintedParticleOptions options, ClientLevel level,
                                       double x, double y, double z, double dx, double dy, double dz) {
            return new DivineSparkParticle(level, x, y, z, dx, dy, dz, options, this.sprites, 11, 0.94f);
        }
    }
}
