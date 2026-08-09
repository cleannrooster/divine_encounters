package com.cleannrooster.divineencounters.neoforge;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.client.DivineEncountersClient;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.cleannrooster.divineencounters.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@Mod(DivineEncounters.MOD_ID)
public final class DivineEncountersNeoForge {
    public DivineEncountersNeoForge(IEventBus modEventBus, ModContainer container) {
        DivineEncounters.init();

        // Fires after registration, so the entity types are bound by the time this runs.
        modEventBus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(ModEntities.VISAGE_OF_WAR.get(), VisageOfWarEntity.createAttributes().build());
            event.put(ModEntities.VISAGE_OF_MALICE.get(), VisageOfMaliceEntity.createAttributes().build());
        });

        if (FMLEnvironment.dist == Dist.CLIENT) {
            DivineEncountersClient.init();
            SlashRenderHook.register();
        }
    }
}
