package com.cleannrooster.divineencounters.particle;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/// A {@link ParticleType} whose options carry colour and scale. The codecs are bound to the instance so
/// each registered type deserialises to options reporting the correct type.
public class TintedParticleType extends ParticleType<TintedParticleOptions> {
    private final MapCodec<TintedParticleOptions> codec;
    private final StreamCodec<? super RegistryFriendlyByteBuf, TintedParticleOptions> streamCodec;

    public TintedParticleType() {
        super(false);
        this.codec = TintedParticleOptions.codec(this);
        this.streamCodec = TintedParticleOptions.streamCodec(this);
    }

    @Override
    public MapCodec<TintedParticleOptions> codec() {
        return this.codec;
    }

    @Override
    public StreamCodec<? super RegistryFriendlyByteBuf, TintedParticleOptions> streamCodec() {
        return this.streamCodec;
    }
}
