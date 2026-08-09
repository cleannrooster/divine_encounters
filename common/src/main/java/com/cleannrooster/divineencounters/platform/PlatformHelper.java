package com.cleannrooster.divineencounters.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/// Loader-specific hooks linked by Architectury's {@code @ExpectPlatform} at build time.
public final class PlatformHelper {
    private PlatformHelper() {
    }

    /// Fabric uses vanilla {@code SpawnEggItem}; NeoForge uses {@code DeferredSpawnEggItem}, which
    /// resolves the entity type lazily. The type is passed as a supplier for the NeoForge path.
    @ExpectPlatform
    public static Item createSpawnEgg(Supplier<? extends EntityType<? extends Mob>> type,
                                      int background, int highlight) {
        throw new AssertionError("@ExpectPlatform method was not replaced at build time.");
    }
}
