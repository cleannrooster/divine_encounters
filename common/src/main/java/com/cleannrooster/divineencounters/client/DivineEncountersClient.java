package com.cleannrooster.divineencounters.client;

import com.cleannrooster.divineencounters.client.combat.SlashEffectManager;
import com.cleannrooster.divineencounters.content.malice.client.MaliceEchoRenderer;
import com.cleannrooster.divineencounters.content.malice.client.VisageOfMaliceRenderer;
import com.cleannrooster.divineencounters.client.particle.DivineSparkParticle;
import com.cleannrooster.divineencounters.content.visage.client.VisageOfWarRenderer;
import com.cleannrooster.divineencounters.particle.TintedParticleOptions;
import com.cleannrooster.divineencounters.registry.ModEntities;
import com.cleannrooster.divineencounters.registry.ModParticles;
import dev.architectury.registry.client.level.entity.EntityRendererRegistry;
import dev.architectury.registry.client.particle.ParticleProviderRegistry;

/// Common client setup, invoked from each loader's client entrypoint. Loader-specific world-render hooks
/// live in the fabric/neoforge source sets and both call
/// {@link SlashEffectManager#render}, so the slash renderer itself is written once.
public final class DivineEncountersClient {
    private DivineEncountersClient() {
    }

    public static void init() {
        EntityRendererRegistry.register(ModEntities.VISAGE_OF_WAR, VisageOfWarRenderer::new);
        EntityRendererRegistry.register(ModEntities.VISAGE_OF_MALICE, VisageOfMaliceRenderer::new);
        EntityRendererRegistry.register(ModEntities.MALICE_ECHO, MaliceEchoRenderer::new);

        ParticleProviderRegistry.register(ModParticles.DIVINE_SPARK,
                (ParticleProviderRegistry.DeferredParticleProvider<TintedParticleOptions>)
                        DivineSparkParticle.SparkProvider::new);
        ParticleProviderRegistry.register(ModParticles.DIVINE_MOTE,
                (ParticleProviderRegistry.DeferredParticleProvider<TintedParticleOptions>)
                        DivineSparkParticle.MoteProvider::new);
        ParticleProviderRegistry.register(ModParticles.MALICE_MOTE,
                (ParticleProviderRegistry.DeferredParticleProvider<TintedParticleOptions>)
                        DivineSparkParticle.MoteProvider::new);

        // Attack visuals: packet receiver + per-tick particle emission for every live slash.
        SlashEffectManager.init();
    }
}
