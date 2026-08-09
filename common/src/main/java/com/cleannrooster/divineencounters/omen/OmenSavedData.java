package com.cleannrooster.divineencounters.omen;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// Which omen each player currently carries.
///
/// Stored server-side against the player's UUID rather than on the player entity, for one practical
/// reason: Architectury on 1.21.1 has no loader-neutral player-data-attachment API. NeoForge's
/// attachments and Fabric's cardinal components are both loader-specific, and `getPersistentData()`
/// is NeoForge-only. A UUID-keyed `SavedData` on the overworld works identically on both, and
/// survives death and dimension changes for free.
///
/// One binding at a time, deliberately. Two armed omens would race each other against the same
/// location, and the resulting "which arena did I get?" would be unreadable.
public final class OmenSavedData extends SavedData {
    private static final String NAME = "divine_encounters_omens";

    /// A binding and the world time it was made.
    ///
    /// `boundAt` of zero means "not yet stamped" — a binding restored from a save written before
    /// omens expired. Those are stamped on first sight rather than discarded, so upgrading does not
    /// silently confiscate an omen somebody is carrying.
    private record Binding(ResourceLocation omen, long boundAt) {
    }

    private final Map<UUID, Binding> bindings = new HashMap<>();
    /// Overworld game time each player last received an omen from a kill.
    ///
    /// Game time rather than wall clock, matching the shrine's cooldown: a server being offline
    /// overnight should not count as waiting.
    private final Map<UUID, Long> lastKillOmen = new HashMap<>();

    /// Always resolved against the overworld, so a binding follows the player between dimensions.
    public static OmenSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OmenSavedData::new, OmenSavedData::load, null), NAME);
    }

    /// Bind an omen, replacing any existing one.
    public void bind(UUID player, OmenType omen, long now) {
        this.bindings.put(player, new Binding(omen.id(), now));
        setDirty();
    }

    public @Nullable OmenType bound(UUID player) {
        var binding = this.bindings.get(player);
        return binding == null ? null : OmenType.get(binding.omen());
    }

    /// World time the current binding was made, or 0 if it has never been stamped.
    public long boundAt(UUID player) {
        var binding = this.bindings.get(player);
        return binding == null ? 0L : binding.boundAt();
    }

    /// Give an unstamped binding a start time. See {@link Binding}.
    public void stamp(UUID player, long now) {
        var binding = this.bindings.get(player);
        if (binding == null || binding.boundAt() != 0L) {
            return;
        }
        this.bindings.put(player, new Binding(binding.omen(), now));
        setDirty();
    }

    public boolean hasBinding(UUID player) {
        return this.bindings.containsKey(player);
    }

    /// Ticks still to wait before a kill can grant another omen, or 0 when ready.
    public long killOmenRemaining(UUID player, long now, long cooldownTicks) {
        var last = this.lastKillOmen.get(player);
        if (last == null || last > now) {
            // A rolled-back world can leave a timestamp in the future; treat that as ready rather
            // than locking the player out permanently.
            return 0L;
        }
        var elapsed = now - last;
        return elapsed >= cooldownTicks ? 0L : cooldownTicks - elapsed;
    }

    public void markKillOmen(UUID player, long now) {
        this.lastKillOmen.put(player, now);
        setDirty();
    }

    /// Consume a binding — called once its arena actually commits, so a refused or abandoned
    /// attempt keeps the omen.
    public void clear(UUID player) {
        if (this.bindings.remove(player) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var bound = new CompoundTag();
        this.bindings.forEach((player, binding) -> {
            var entry = new CompoundTag();
            entry.putString("Omen", binding.omen().toString());
            entry.putLong("BoundAt", binding.boundAt());
            bound.put(player.toString(), entry);
        });
        tag.put("Bindings", bound);

        var cooldowns = new CompoundTag();
        this.lastKillOmen.forEach((player, when) -> cooldowns.putLong(player.toString(), when));
        tag.put("KillOmenCooldowns", cooldowns);
        return tag;
    }

    private static OmenSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new OmenSavedData();
        var bound = tag.getCompound("Bindings");
        for (var key : bound.getAllKeys()) {
            // Two shapes are accepted: a compound (current), and a bare string from before bindings
            // expired. The old shape loads with no timestamp and is stamped on first sight.
            ResourceLocation omen;
            long boundAt = 0L;
            if (bound.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                var entry = bound.getCompound(key);
                omen = ResourceLocation.tryParse(entry.getString("Omen"));
                boundAt = entry.getLong("BoundAt");
            } else {
                omen = ResourceLocation.tryParse(bound.getString(key));
            }
            if (omen == null) {
                continue;
            }
            try {
                data.bindings.put(UUID.fromString(key), new Binding(omen, boundAt));
            } catch (IllegalArgumentException ignored) {
                // A malformed key is not worth failing a world load over.
            }
        }

        var cooldowns = tag.getCompound("KillOmenCooldowns");
        for (var key : cooldowns.getAllKeys()) {
            try {
                data.lastKillOmen.put(UUID.fromString(key), cooldowns.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // As above. A lost cooldown costs one early omen, not a world.
            }
        }
        return data;
    }
}
