package com.cleannrooster.divineencounters.encounter;

import com.cleannrooster.divineencounters.encounter.structure.ManifestationEffects;
import com.cleannrooster.divineencounters.encounter.structure.ManifestationOrder;
import com.cleannrooster.divineencounters.encounter.structure.PersistenceMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.function.Supplier;

/// Everything that distinguishes one arena from another, as data.
///
/// Two arenas already differ in almost every axis — War's tower climbs out of the ground while
/// Malice's cage closes inward around whoever is inside it — and neither needs a line of bespoke
/// code to do it. Adding a third is one of these plus a template.
///
/// @param id           registry-style identifier, used for persistence and commands
/// @param templateId   the structure template to manifest
/// @param order        how it assembles; the main thing that gives an arena its character
/// @param batchCount   assembly steps; with `tickInterval` this sets the total duration
/// @param tickInterval ticks between batches
/// @param persistence  whether the terrain is restored afterward
/// @param boss         the entity type to spawn once the arena is sealed
/// @param warningTicks how long the player gets between conditions being met and the arena
///                     committing — the beat in which they can still walk away
/// @param effects      per-batch presentation, so emergence follows the region actually appearing
public record ArenaDefinition(
        ResourceLocation id,
        ResourceLocation templateId,
        ManifestationOrder order,
        int batchCount,
        int tickInterval,
        PersistenceMode persistence,
        Supplier<EntityType<? extends Mob>> boss,
        int warningTicks,
        ManifestationEffects effects
) {
    public static Builder builder(ResourceLocation id, ResourceLocation templateId) {
        return new Builder(id, templateId);
    }

    /// Total assembly time in ticks, for sanity-checking a definition against its intended feel.
    public int assemblyTicks() {
        return this.batchCount * this.tickInterval;
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final ResourceLocation templateId;
        private ManifestationOrder order = ManifestationOrder.BOTTOM_TO_TOP;
        private int batchCount = 24;
        private int tickInterval = 2;
        // Arenas erupt wherever a player happens to be standing, so putting the world back is the
        // only defensible default.
        private PersistenceMode persistence = PersistenceMode.RESTORE_ORIGINAL;
        private Supplier<EntityType<? extends Mob>> boss;
        private int warningTicks = 50;
        private ManifestationEffects effects = ManifestationEffects.NONE;

        private Builder(ResourceLocation id, ResourceLocation templateId) {
            this.id = id;
            this.templateId = templateId;
        }

        public Builder order(ManifestationOrder order) {
            this.order = order;
            return this;
        }

        public Builder pacing(int batchCount, int tickInterval) {
            this.batchCount = batchCount;
            this.tickInterval = tickInterval;
            return this;
        }

        public Builder persistence(PersistenceMode persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder boss(Supplier<EntityType<? extends Mob>> boss) {
            this.boss = boss;
            return this;
        }

        public Builder warningTicks(int ticks) {
            this.warningTicks = ticks;
            return this;
        }

        public Builder effects(ManifestationEffects effects) {
            this.effects = effects;
            return this;
        }

        public ArenaDefinition build() {
            return new ArenaDefinition(this.id, this.templateId, this.order, this.batchCount,
                    this.tickInterval, this.persistence, this.boss, this.warningTicks, this.effects);
        }
    }
}
