package com.cleannrooster.divineencounters.platform.neoforge;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;

import java.util.function.Supplier;

/// NeoForge implementation of {@link com.cleannrooster.divineencounters.platform.PlatformHelper}.
/// {@code DeferredSpawnEggItem} resolves the entity type lazily, so it is safe even if the item is built
/// before the entity type is bound.
public final class PlatformHelperImpl {
    private PlatformHelperImpl() {
    }

    public static Item createSpawnEgg(Supplier<? extends EntityType<? extends Mob>> type,
                                      int background, int highlight) {
        return new DeferredSpawnEggItem(type, background, highlight, new Item.Properties());
    }
}
