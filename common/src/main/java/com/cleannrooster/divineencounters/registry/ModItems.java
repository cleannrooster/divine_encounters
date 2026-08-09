package com.cleannrooster.divineencounters.registry;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.omen.DivineOmens;
import com.cleannrooster.divineencounters.omen.OmenItem;
import com.cleannrooster.divineencounters.platform.PlatformHelper;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(DivineEncounters.MOD_ID, Registries.ITEM);

    // Spawn-egg construction differs per loader (Fabric SpawnEggItem vs NeoForge DeferredSpawnEggItem),
    // so it goes through @ExpectPlatform.
    public static final RegistrySupplier<Item> VISAGE_OF_WAR_SPAWN_EGG =
            ITEMS.register("visage_of_war_spawn_egg",
                    () -> PlatformHelper.createSpawnEgg(ModEntities.VISAGE_OF_WAR, 0xf2e3b0, 0x8c2f2f));

    public static final RegistrySupplier<Item> VISAGE_OF_MALICE_SPAWN_EGG =
            ITEMS.register("visage_of_malice_spawn_egg",
                    () -> PlatformHelper.createSpawnEgg(ModEntities.VISAGE_OF_MALICE, 0x140d1c, 0x7d3fa0));

    // Omens arm a player rather than spawning anything: the arena appears at the next place the
    // world will accept one. Rare by intent — one is a whole encounter.
    public static final RegistrySupplier<Item> OMEN_OF_WAR =
            ITEMS.register("omen_of_war", () -> new OmenItem(DivineOmens.OMEN_OF_WAR,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC)));

    public static final RegistrySupplier<Item> OMEN_OF_MALICE =
            ITEMS.register("omen_of_malice", () -> new OmenItem(DivineOmens.OMEN_OF_MALICE,
                    new Item.Properties().stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC)));

    private ModItems() {
    }
}
