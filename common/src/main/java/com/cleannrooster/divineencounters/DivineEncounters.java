package com.cleannrooster.divineencounters;

import com.cleannrooster.divineencounters.combat.DivineAttacks;
import com.cleannrooster.divineencounters.config.DivineConfig;
import com.cleannrooster.divineencounters.content.malice.entity.VisageOfMaliceEntity;
import com.cleannrooster.divineencounters.content.visage.entity.VisageOfWarEntity;
import com.cleannrooster.divineencounters.combat.net.AttackNetwork;
import com.cleannrooster.divineencounters.encounter.EncounterManager;
import com.cleannrooster.divineencounters.omen.DivineOmens;
import com.cleannrooster.divineencounters.omen.OmenCommand;
import com.cleannrooster.divineencounters.omen.OmenDrops;
import com.cleannrooster.divineencounters.omen.OmenWatcher;
import com.cleannrooster.divineencounters.shrine.ShrineInteraction;
import com.cleannrooster.divineencounters.registry.ModCreativeTabs;
import com.cleannrooster.divineencounters.registry.ModEntities;
import com.cleannrooster.divineencounters.registry.ModItems;
import com.cleannrooster.divineencounters.registry.ModParticles;
import com.cleannrooster.divineencounters.registry.ModSounds;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Common entrypoint for Divine Encounters. Each loader calls {@link #init()} from its own entrypoint;
/// loader-specific wiring (entity attributes, client renderers, render hooks) stays in the fabric/neoforge
/// source sets.
///
/// Layout: the shared combat framework lives under {@code combat} (see
/// {@link com.cleannrooster.divineencounters.combat.DirectionalAttack}); each boss's own code lives under
/// {@code content.<boss>}. Adding a boss means a new {@code content.<name>} package plus entries in the
/// mod-wide registry classes — the attack system itself should not need to change.
public final class DivineEncounters {
    public static final String MOD_ID = "divine_encounters";
    public static final Logger LOGGER = LoggerFactory.getLogger("Divine Encounters");

    private DivineEncounters() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static void init() {
        // First, before anything reads a number from it. Attack definitions and attribute suppliers
        // are both built during registration below, and they bake their values in as they go.
        DivineConfig.load();

        ModEntities.ENTITY_TYPES.register();
        ModItems.ITEMS.register();
        ModParticles.PARTICLE_TYPES.register();
        ModSounds.SOUND_EVENTS.register();
        ModCreativeTabs.TABS.register();
        ModCreativeTabs.appendItems();

        // Attack definitions must be registered identically on both sides: the server resolves damage from
        // them, the client rebuilds the visual from the same entry using only the id carried by the packet.
        DivineAttacks.bootstrap();
        AttackNetwork.register();
        // Arenas and the omens that summon them. Must register before items, which reference them.
        DivineOmens.bootstrap();
        // Server-tick driver for live arenas, plus clean teardown on shutdown.
        EncounterManager.register();
        // Watches armed players for somewhere their omen will take hold.
        OmenWatcher.register();
        // Omens that arrive by killing rather than by praying.
        OmenDrops.register();
        OmenCommand.register();
        // Lodestone + lectern shrines: an empty-handed click on the base submits the prayer above.
        ShrineInteraction.register();

        // Build the attribute suppliers once, purely so every configurable key has declared itself
        // before the file is written.
        //
        // The two loaders build these at different times — Fabric immediately after this method
        // returns, NeoForge from a mod-bus event much later — so neither is a point at which the
        // config could harvest them. Doing it here is the only moment both have in common, and the
        // builders are cheap and side-effect-free apart from the declarations themselves. Without
        // this, a freshly generated config would list the attack damages and no boss attributes.
        VisageOfWarEntity.createAttributes();
        VisageOfMaliceEntity.createAttributes();

        // Write the file back now that every attack and attribute has announced itself, so the
        // config on disk always lists everything that can be changed — including keys added by an
        // update to a config written for an older version.
        DivineConfig.save();

        LOGGER.info("Divine Encounters common init complete.");
    }
}
