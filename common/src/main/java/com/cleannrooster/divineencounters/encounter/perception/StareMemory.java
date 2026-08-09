package com.cleannrooster.divineencounters.encounter.perception;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Short rolling memory of where each player has recently been looking.
///
/// This exists for one job: stopping a boss from materialising in a spot the player has been
/// staring at. Checking only the *current* look direction is not enough — a player sweeping their
/// camera passes through a direction for a single tick, which should not disqualify it, while a
/// player who has held a corner in view for a second genuinely would notice something appear there.
///
/// Keeping a couple of seconds of samples distinguishes those two cases cheaply.
public final class StareMemory {
    /// Roughly two seconds. Long enough to mean "has been watching", short enough that turning
    /// away frees the space up promptly.
    private static final int CAPACITY = 40;

    private record Sample(Vec3 eye, Vec3 look) {
    }

    private final Map<UUID, Deque<Sample>> samples = new HashMap<>();

    /// Record this tick's look direction for every participating player.
    public void tick(List<? extends Player> participants) {
        for (var player : participants) {
            var history = this.samples.computeIfAbsent(player.getUUID(),
                    id -> new ArrayDeque<>(CAPACITY));
            history.addFirst(new Sample(player.getEyePosition(), player.getLookAngle()));
            while (history.size() > CAPACITY) {
                history.removeLast();
            }
        }
        this.samples.keySet().removeIf(id -> participants.stream()
                .noneMatch(player -> player.getUUID().equals(id)));
    }

    /// Whether any player has held `position` inside a `coneDegrees` cone for the last `ticks`.
    ///
    /// Requires an unbroken run: one sample looking elsewhere is enough to say they were not
    /// staring. A player with less history than `ticks` counts as not staring, so a freshly joined
    /// player never blocks a manifestation.
    public boolean anyoneStaringAt(Vec3 position, float coneDegrees, int ticks) {
        for (var history : this.samples.values()) {
            if (staringAt(history, position, coneDegrees, ticks)) {
                return true;
            }
        }
        return false;
    }

    private static boolean staringAt(Deque<Sample> history, Vec3 position, float coneDegrees,
                                     int ticks) {
        if (history.size() < ticks) {
            return false;
        }
        var checked = 0;
        for (var sample : history) {
            if (checked++ >= ticks) {
                break;
            }
            var toPosition = position.subtract(sample.eye());
            if (toPosition.lengthSqr() < 1.0e-8) {
                continue;
            }
            var dot = Mth.clamp(sample.look().dot(toPosition.normalize()), -1.0, 1.0);
            if (Math.toDegrees(Math.acos(dot)) > coneDegrees) {
                return false;
            }
        }
        return true;
    }

    public void clear() {
        this.samples.clear();
    }
}
