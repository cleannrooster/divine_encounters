package com.cleannrooster.divineencounters.shrine;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// When each player last had a prayer answered.
///
/// **The cooldown belongs to the player, not to the shrine and not to the book.** That is the whole
/// point: otherwise a player could keep a stack of pre-written prayers and walk a circuit of
/// shrines, or hand one book around a server. Keyed by UUID on the overworld, so it survives
/// logout, death, dimension changes and restarts without any of them being special-cased.
///
/// Time is measured in overworld game time. That advances only while the world is running, which is
/// the right semantic for a gameplay cooldown — a server being offline overnight should not count
/// as waiting.
public final class PrayerSavedData extends SavedData {
    private static final String NAME = "divine_encounters_prayers";

    /// Thirty minutes at twenty ticks a second.
    public static final long COOLDOWN_TICKS = 30L * 60L * 20L;

    private final Map<UUID, Long> lastPrayer = new HashMap<>();

    public static PrayerSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PrayerSavedData::new, PrayerSavedData::load, null), NAME);
    }

    /// Ticks still to wait, or 0 when the shrine will answer.
    public long remaining(UUID player, long now) {
        var last = this.lastPrayer.get(player);
        if (last == null) {
            return 0L;
        }
        // A rolled-back or re-created world can leave a timestamp in the future; treat that as
        // ready rather than locking the player out for eternity.
        if (last > now) {
            return 0L;
        }
        var elapsed = now - last;
        return elapsed >= COOLDOWN_TICKS ? 0L : COOLDOWN_TICKS - elapsed;
    }

    public boolean isReady(UUID player, long now) {
        return remaining(player, now) <= 0L;
    }

    public void markPrayed(UUID player, long now) {
        this.lastPrayer.put(player, now);
        setDirty();
    }

    /// For debug commands — lets a tester retry without waiting half an hour.
    public void clear(UUID player) {
        if (this.lastPrayer.remove(player) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var entries = new CompoundTag();
        this.lastPrayer.forEach((player, time) -> entries.putLong(player.toString(), time));
        tag.put("LastPrayer", entries);
        return tag;
    }

    private static PrayerSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new PrayerSavedData();
        var entries = tag.getCompound("LastPrayer");
        for (var key : entries.getAllKeys()) {
            try {
                data.lastPrayer.put(UUID.fromString(key), entries.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // A malformed key is not worth failing a world load over.
            }
        }
        return data;
    }
}
