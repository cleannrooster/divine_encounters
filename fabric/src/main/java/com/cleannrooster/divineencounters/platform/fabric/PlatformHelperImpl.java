package com.cleannrooster.divineencounters.platform.fabric;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.function.Supplier;

/// Fabric implementation of {@link com.cleannrooster.divineencounters.platform.PlatformHelper}.
/// Architectury's @ExpectPlatform links to this class by the `<package>.fabric.<Class>Impl` convention.
public final class PlatformHelperImpl {
    private PlatformHelperImpl() {
    }

    public static Item createSpawnEgg(Supplier<? extends EntityType<? extends Mob>> type,
                                      int background, int highlight) {
        return new SpawnEggItem(type.get(), background, highlight, new Item.Properties());
    }
}
