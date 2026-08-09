package com.cleannrooster.divineencounters.registry;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.content.malice.entity.MaliceEchoEntity;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(DivineEncounters.MOD_ID, Registries.ENTITY_TYPE);

    /// Registered size must match the entity's own dimensions, or a freshly spawned Visage keeps this box
    /// until her first refresh.
    public static final RegistrySupplier<EntityType<VisageOfWarEntity>> VISAGE_OF_WAR =
            ENTITY_TYPES.register("visage_of_war", () -> EntityType.Builder
                    .<VisageOfWarEntity>of(VisageOfWarEntity::new, MobCategory.MONSTER)
                    .sized(1.0f, 2.6f)
                    .clientTrackingRange(12)
                    .fireImmune()
                    // 1.21.1: EntityType.Builder.build takes the registry-name String, not a ResourceKey.
                    .build("visage_of_war"));

    /// Registered size must match the entity's own dimensions, or a freshly spawned Malice keeps
    /// this box until its first refresh.
    public static final RegistrySupplier<EntityType<VisageOfMaliceEntity>> VISAGE_OF_MALICE =
            ENTITY_TYPES.register("visage_of_malice", () -> EntityType.Builder
                    .<VisageOfMaliceEntity>of(VisageOfMaliceEntity::new, MobCategory.MONSTER)
                    .sized(0.9f, 2.9f)
                    .clientTrackingRange(12)
                    .fireImmune()
                    .build("visage_of_malice"));

    /// A false silhouette. Renders and fades; can do nothing else. See MaliceEchoEntity for why
    /// this is a real entity rather than a client-side effect.
    public static final RegistrySupplier<EntityType<MaliceEchoEntity>> MALICE_ECHO =
            ENTITY_TYPES.register("malice_echo", () -> EntityType.Builder
                    .<MaliceEchoEntity>of(MaliceEchoEntity::new, MobCategory.MISC)
                    .sized(0.9f, 2.9f)
                    .clientTrackingRange(12)
                    .updateInterval(4)
                    .noSummon()
                    .build("malice_echo"));

    private ModEntities() {
    }
}
