package com.cleannrooster.divineencounters.registry;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.particle.TintedParticleType;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(DivineEncounters.MOD_ID, Registries.PARTICLE_TYPE);

    /// The workhorse of the attack visuals: a small tinted spark that shrinks as it dies. Every slash,
    /// sweep and thrust scatters these along its own geometry, so one particle serves every attack.
    public static final RegistrySupplier<TintedParticleType> DIVINE_SPARK =
            PARTICLE_TYPES.register("divine_spark", TintedParticleType::new);

    /// Heavier, slower mote used for the leading edge of a swing and for impact bursts.
    public static final RegistrySupplier<TintedParticleType> DIVINE_MOTE =
            PARTICLE_TYPES.register("divine_mote", TintedParticleType::new);

    /// Cold violet mote used for Malice's cues and slash trails — the visual inverse of the gold
    /// spark, and deliberately dimmer so it reads in a dark arena without lighting it up.
    public static final RegistrySupplier<TintedParticleType> MALICE_MOTE =
            PARTICLE_TYPES.register("malice_mote", TintedParticleType::new);

    private ModParticles() {
    }
}
