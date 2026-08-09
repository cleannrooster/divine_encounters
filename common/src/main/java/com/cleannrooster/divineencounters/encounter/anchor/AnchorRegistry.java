package com.cleannrooster.divineencounters.encounter.anchor;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The authored positions belonging to one live encounter, grouped by kind.
///
/// Populated once when the arena structure is placed and torn down with it. Bosses query it; they
/// never search the world for somewhere to stand, and they never modify it.
///
/// Occupancy is tracked so two things cannot claim the same perch, and so a boss can avoid
/// returning to the anchor it just left.
public final class AnchorRegistry {
    private final Map<AnchorKind, List<AnchorPoint>> byKind = new EnumMap<>(AnchorKind.class);
    private final Set<String> claimed = new HashSet<>();

    public void add(AnchorPoint anchor) {
        this.byKind.computeIfAbsent(anchor.kind(), kind -> new ArrayList<>()).add(anchor);
    }

    public void addAll(Iterable<AnchorPoint> anchors) {
        anchors.forEach(this::add);
    }

    /// Every anchor of a kind, including claimed ones.
    public List<AnchorPoint> all(AnchorKind kind) {
        return Collections.unmodifiableList(this.byKind.getOrDefault(kind, List.of()));
    }

    /// Anchors of a kind that nothing currently occupies.
    public List<AnchorPoint> available(AnchorKind kind) {
        var candidates = this.byKind.get(kind);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        var free = new ArrayList<AnchorPoint>(candidates.size());
        for (var anchor : candidates) {
            if (!this.claimed.contains(anchor.id())) {
                free.add(anchor);
            }
        }
        return free;
    }

    /// The single anchor of a unique kind (arena centre, boss spawn), or null when the template
    /// didn't author one.
    public @Nullable AnchorPoint single(AnchorKind kind) {
        var candidates = this.byKind.get(kind);
        return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
    }

    public boolean claim(AnchorPoint anchor) {
        return this.claimed.add(anchor.id());
    }

    public void release(AnchorPoint anchor) {
        this.claimed.remove(anchor.id());
    }

    public void releaseAll() {
        this.claimed.clear();
    }

    /// True when no arena has been registered — the normal state when a boss is summoned directly
    /// rather than through its encounter. Callers use this to degrade gracefully rather than
    /// erroring: an arena-less fight simply loses the perch-based options.
    public boolean isEmpty() {
        return this.byKind.isEmpty();
    }

    public void clear() {
        this.byKind.clear();
        this.claimed.clear();
    }
}
