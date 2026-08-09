package com.cleannrooster.divineencounters.registry;

import com.cleannrooster.divineencounters.DivineEncounters;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;
import java.util.stream.Stream;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(DivineEncounters.MOD_ID, Registries.CREATIVE_MODE_TAB);

    // CreativeModeTab.builder() (no-arg) is a Fabric-API extension, so loader-neutral common code goes
    // through Architectury's CreativeTabRegistry instead.
    public static final RegistrySupplier<CreativeModeTab> MAIN_TAB =
            TABS.register("divine_encounters", () -> CreativeTabRegistry.create(
                    Component.translatable("itemGroup.divine_encounters"),
                    () -> new ItemStack(ModItems.VISAGE_OF_WAR_SPAWN_EGG.get())));

    private ModCreativeTabs() {
    }

    public static void appendItems() {
        CreativeTabRegistry.appendStack(MAIN_TAB, Stream.<Supplier<ItemStack>>of(
                () -> new ItemStack(ModItems.VISAGE_OF_WAR_SPAWN_EGG.get()),
                () -> new ItemStack(ModItems.VISAGE_OF_MALICE_SPAWN_EGG.get()),
                () -> new ItemStack(ModItems.OMEN_OF_WAR.get()),
                () -> new ItemStack(ModItems.OMEN_OF_MALICE.get())));
    }
}
