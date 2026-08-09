package com.cleannrooster.divineencounters.encounter.structure;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/// Per-batch presentation hook, fired with the bounds of the blocks that just appeared.
///
/// Taking the *batch's* bounds rather than the whole structure's is the entire point: effects
/// follow the region that is actually emerging, so a cage sounds and looks like it is erupting
/// section by section instead of playing one undifferentiated noise across the whole arena.
@FunctionalInterface
public interface ManifestationEffects {
    ManifestationEffects NONE = (level, bounds, index, count, reversing) -> {
    };

    /// @param bounds    the world-space extent of the batch just placed (or removed)
    /// @param index     which batch, 0-based
    /// @param count     how many batches in total, for ramping intensity toward the finale
    /// @param reversing true during retraction, so a hook can play the sequence differently
    void onBatch(ServerLevel level, BoundingBox bounds, int index, int count, boolean reversing);
}
