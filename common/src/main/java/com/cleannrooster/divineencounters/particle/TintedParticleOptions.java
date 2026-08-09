package com.cleannrooster.divineencounters.particle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/// Particle options carrying an RGB tint and a size, so one sprite can serve every attack in the mod at
/// whatever colour and scale its {@link com.cleannrooster.divineencounters.combat.ParticleProfile} asks
/// for. The owning {@link TintedParticleType} binds the codecs to itself.
public class TintedParticleOptions implements ParticleOptions {
    private final ParticleType<TintedParticleOptions> type;
    private final float red;
    private final float green;
    private final float blue;
    private final float scale;

    public TintedParticleOptions(ParticleType<TintedParticleOptions> type,
                                 float red, float green, float blue, float scale) {
        this.type = type;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.scale = scale;
    }

    @Override
    public ParticleType<?> getType() {
        return this.type;
    }

    public float red() {
        return this.red;
    }

    public float green() {
        return this.green;
    }

    public float blue() {
        return this.blue;
    }

    public float scale() {
        return this.scale;
    }

    public static MapCodec<TintedParticleOptions> codec(ParticleType<TintedParticleOptions> type) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("red").forGetter(TintedParticleOptions::red),
                Codec.FLOAT.fieldOf("green").forGetter(TintedParticleOptions::green),
                Codec.FLOAT.fieldOf("blue").forGetter(TintedParticleOptions::blue),
                Codec.FLOAT.fieldOf("scale").forGetter(TintedParticleOptions::scale)
        ).apply(instance, (r, g, b, s) -> new TintedParticleOptions(type, r, g, b, s)));
    }

    public static StreamCodec<? super RegistryFriendlyByteBuf, TintedParticleOptions> streamCodec(
            ParticleType<TintedParticleOptions> type) {
        return StreamCodec.composite(
                ByteBufCodecs.FLOAT, TintedParticleOptions::red,
                ByteBufCodecs.FLOAT, TintedParticleOptions::green,
                ByteBufCodecs.FLOAT, TintedParticleOptions::blue,
                ByteBufCodecs.FLOAT, TintedParticleOptions::scale,
                (r, g, b, s) -> new TintedParticleOptions(type, r, g, b, s));
    }
}
