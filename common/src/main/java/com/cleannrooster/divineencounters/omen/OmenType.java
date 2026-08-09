package com.cleannrooster.divineencounters.omen;

import com.cleannrooster.divineencounters.DivineEncounters;
import com.cleannrooster.divineencounters.encounter.ArenaDefinition;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/// One kind of omen: what it summons, and where it is willing to summon it.
///
/// An omen is the binding between a player's intent and an arena. Binding one does not build
/// anything — it arms the player, and the arena appears at the next place the world will accept it.
///
/// Eligible terrain is expressed as a **biome tag** rather than a hardcoded list, so a pack can add
/// biomes to `divine_encounters:omen_sites/war` without touching code. Each omen wants somewhere
/// different: War a wide flat plain to raise a tower over, Malice a dark forest to entangle.
///
/// @param id           registry-style identifier
/// @param biomes       tag of biomes this omen will manifest in
/// @param arena        the arena it summons
/// @param footprint    half-extent in blocks that must be flat and unspoilt
/// @param maxRelief    the greatest height variation tolerated across the footprint
public record OmenType(
        ResourceLocation id,
        TagKey<Biome> biomes,
        Supplier<ArenaDefinition> arena,
        int footprint,
        int maxRelief
) {
    private static final Map<ResourceLocation, OmenType> TYPES = new LinkedHashMap<>();

    public static OmenType register(OmenType type) {
        var previous = TYPES.putIfAbsent(type.id(), type);
        if (previous != null && previous != type) {
            throw new IllegalStateException("Duplicate omen id: " + type.id());
        }
        return type;
    }

    public static @Nullable OmenType get(ResourceLocation id) {
        return TYPES.get(id);
    }

    public static Collection<OmenType> all() {
        return Collections.unmodifiableCollection(TYPES.values());
    }

    /// Helper for declaring an omen and its biome tag together.
    public static OmenType of(String name, Supplier<ArenaDefinition> arena, int footprint,
                              int maxRelief) {
        var id = DivineEncounters.id(name);
        var tag = TagKey.create(Registries.BIOME, DivineEncounters.id("omen_sites/" + name));
        return new OmenType(id, tag, arena, footprint, maxRelief);
    }

    /// Translation key for the bound-omen message and item tooltip.
    public String descriptionId() {
        return "omen." + this.id().getNamespace() + "." + this.id().getPath();
    }
}
