package com.cleannrooster.divineencounters.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/// Per-level persistence and ticking for live arena encounters.
///
/// Holding the live objects here rather than in a parallel manager keeps one owner: whatever is in
/// this list is exactly what gets ticked and exactly what gets saved, so the two can never drift.
///
/// The restart contract this exists to honour:
/// - stopped mid-assembly, it resumes from the batch it reached;
/// - stopped mid-fight, the sealed arena and its boss come back without a second boss spawning;
/// - already completed, nothing is rebuilt.
public final class EncounterSavedData extends SavedData {
    private static final String NAME = "divine_encounters_arenas";

    private final List<ArenaEncounter> encounters = new ArrayList<>();
    /// Saved encounters not yet rehydrated. `load` is static and has no level to work with, so the
    /// tags are parked here and turned into real encounters on the first tick.
    private final List<CompoundTag> pending = new ArrayList<>();

    public static EncounterSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EncounterSavedData::new, EncounterSavedData::load, null), NAME);
    }

    /// Register a newly created encounter. It begins in its warning beat.
    public void add(ArenaEncounter encounter) {
        this.encounters.add(encounter);
        setDirty();
    }

    public List<ArenaEncounter> encounters() {
        return List.copyOf(this.encounters);
    }

    /// Whether any live encounter is near a position — used to stop arenas piling up on each other.
    public boolean hasEncounterNear(BlockPos pos, double radius) {
        var radiusSqr = radius * radius;
        for (var encounter : this.encounters) {
            if (!encounter.isFinished() && encounter.origin().distSqr(pos) <= radiusSqr) {
                return true;
            }
        }
        return false;
    }

    /// Advance every live encounter, hydrating any restored from disk first.
    public void tick(ServerLevel level) {
        hydrate(level);
        if (this.encounters.isEmpty()) {
            return;
        }
        for (Iterator<ArenaEncounter> it = this.encounters.iterator(); it.hasNext(); ) {
            var encounter = it.next();
            if (!encounter.tick(level)) {
                it.remove();
                setDirty();
            }
        }
        // Cheap insurance: encounters mutate constantly, and losing progress to an unflagged save
        // would strand a half-built arena.
        if (level.getGameTime() % 100 == 0) {
            setDirty();
        }
    }

    private void hydrate(ServerLevel level) {
        if (this.pending.isEmpty()) {
            return;
        }
        for (var tag : this.pending) {
            var arenaId = ResourceLocation.tryParse(tag.getString("Arena"));
            var definition = arenaId == null ? null : ArenaRegistry.lookupForLoad(arenaId);
            var origin = NbtUtils.readBlockPos(tag, "Origin");
            if (definition == null || origin.isEmpty()) {
                continue;
            }
            var encounter = new ArenaEncounter(definition, origin.get());
            encounter.load(level, tag);
            if (!encounter.isFinished()) {
                this.encounters.add(encounter);
            }
        }
        this.pending.clear();
        setDirty();
    }

    /// Tear everything down cleanly on shutdown, restoring terrain rather than leaving half-built
    /// arenas behind.
    public void abortAll(ServerLevel level) {
        for (var encounter : this.encounters) {
            encounter.abort(level);
        }
        this.encounters.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var encounter : this.encounters) {
            if (!encounter.isFinished()) {
                list.add(encounter.save(new CompoundTag()));
            }
        }
        // Anything not yet hydrated is preserved verbatim, so a level that saved before ticking
        // does not silently drop its encounters.
        list.addAll(this.pending);
        tag.put("Encounters", list);
        return tag;
    }

    private static EncounterSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new EncounterSavedData();
        var list = tag.getList("Encounters", Tag.TAG_COMPOUND);
        for (var i = 0; i < list.size(); i++) {
            data.pending.add(list.getCompound(i));
        }
        return data;
    }
}
