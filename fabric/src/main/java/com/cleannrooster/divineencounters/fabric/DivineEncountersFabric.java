package com.cleannrooster.divineencounters.fabric;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.cleannrooster.divineencounters.registry.ModEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;

public final class DivineEncountersFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DivineEncounters.init();

        // ENTITY_TYPES are already registered (Architectury registers eagerly on Fabric), so .get() is valid.
        FabricDefaultAttributeRegistry.register(ModEntities.VISAGE_OF_WAR.get(),
                VisageOfWarEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(ModEntities.VISAGE_OF_MALICE.get(),
                VisageOfMaliceEntity.createAttributes());
    }
}
