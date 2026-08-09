package com.cleannrooster.divineencounters.encounter.perception;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// Tracks, over time, whether *anyone* is watching. One instance per boss, ticked server-side.
///
/// Three things make this more than a per-tick line-of-sight call:
///
/// 1. **Hysteresis.** Each player's previous answer is remembered and fed back into
///    {@link ObservationCheck}, so a player holding the boss at the edge of their screen gets a
///    stable answer instead of one that flips every tick.
/// 2. **A grace period.** Observation has to be absent for {@link GloomProfile#graceTicks} before
///    it counts as genuinely lost. A flick of the mouse across the room is not an opportunity.
/// 3. **Multiplayer as an OR.** The boss is constrained if *any* eligible player sees it. A group
///    that covers each other's blind spots really does suppress the mechanic — that is intended,
///    and it makes coordination the counterplay rather than a loophole.
///
/// The tracker deliberately knows nothing about what losing observation *enables*. It answers
/// "is anyone looking, and for how long has nobody been", and the presence system decides what
/// that is worth.
public final class ObservationTracker {
    /// Blocks that do not conceal the tracked entity from a player.
    ///
    /// Defaults to none. A boss whose whole mechanic turns on being observed cannot afford for
    /// foliage to count as concealment — see {@link LineOfSight}.
    private java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> seeThrough =
            ObservationCheck.NOTHING_IS_TRANSPARENT;

    public ObservationTracker seeThrough(
            java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> predicate) {
        this.seeThrough = predicate;
        return this;
    }

    /// The current sight predicate. Exposed so the wiring between it and the movement-passability
    /// predicate can be asserted — the two must stay the same object.
    public java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> seesThrough() {
        return this.seeThrough;
    }

    /// Last answer per player, so each gets its own hysteresis band.
    private final Map<UUID, Boolean> lastAnswer = new HashMap<>();
    private final Set<UUID> seenThisTick = new HashSet<>();

    private boolean observed = true;
    private int observedTicks;
    private int unobservedTicks;
    private @Nullable UUID lastObserver;
    /// Forces the observed answer for a while regardless of where anyone is looking.
    private int pinnedTicks;

    /// Advance one server tick against the current set of eligible players.
    public void tick(Entity target, List<? extends Player> participants, GloomProfile profile) {
        if (this.pinnedTicks > 0) {
            this.pinnedTicks--;
            markObserved(this.lastObserver);
            return;
        }

        this.seenThisTick.clear();
        var anyone = false;
        UUID observer = null;

        for (var player : participants) {
            var id = player.getUUID();
            this.seenThisTick.add(id);
            var previous = this.lastAnswer.getOrDefault(id, Boolean.FALSE);
            var now = ObservationCheck.isObserving(player, target, profile, previous,
                    this.seeThrough);
            this.lastAnswer.put(id, now);
            if (now && !anyone) {
                anyone = true;
                observer = id;
            }
        }

        // Players who left, died, or went spectator stop contributing — and stop leaking entries.
        this.lastAnswer.keySet().retainAll(this.seenThisTick);

        accumulate(anyone, observer);
    }

    /// The timing half of the tracker, independent of how the observation verdict was reached.
    /// Split out so the grace-period and pin behaviour can be verified headlessly, without
    /// constructing a Level and real players.
    public void tickWithVerdict(boolean anyoneObserving) {
        if (this.pinnedTicks > 0) {
            this.pinnedTicks--;
            markObserved(this.lastObserver);
            return;
        }
        accumulate(anyoneObserving, this.lastObserver);
    }

    private void accumulate(boolean anyoneObserving, @Nullable UUID observer) {
        if (anyoneObserving) {
            markObserved(observer);
        } else {
            this.observed = false;
            this.observedTicks = 0;
            this.unobservedTicks++;
        }
    }

    private void markObserved(@Nullable UUID observer) {
        this.observed = true;
        this.unobservedTicks = 0;
        this.observedTicks++;
        if (observer != null) {
            this.lastObserver = observer;
        }
    }

    /// True the moment anyone is looking. Note this flips on the *first* observed tick — becoming
    /// seen is instant, while becoming unseen has to survive the grace period.
    public boolean isObserved() {
        return this.observed;
    }

    /// Consecutive ticks with nobody watching.
    public int unobservedTicks() {
        return this.unobservedTicks;
    }

    /// Consecutive ticks with somebody watching.
    public int observedTicks() {
        return this.observedTicks;
    }

    /// Whether observation has been absent long enough to count as genuinely broken. This is the
    /// gate on a boss gaining any unseen privilege.
    public boolean hasLostObservation(GloomProfile profile) {
        return !this.observed && this.unobservedTicks >= profile.graceTicks();
    }

    /// Fraction of the grace period elapsed, 0-1 — for ramping cues as the window approaches.
    public float lossProgress(GloomProfile profile) {
        if (this.observed || profile.graceTicks() <= 0) {
            return this.observed ? 0.0f : 1.0f;
        }
        return Math.min(1.0f, (float) this.unobservedTicks / profile.graceTicks());
    }

    public @Nullable UUID lastObserver() {
        return this.lastObserver;
    }

    /// Force the observed answer for `ticks`, regardless of where anyone is actually looking.
    ///
    /// This is how landing a hit pins the boss visible: successful aggression should restore
    /// information, and it has to override the normal check or a player who lands a blow while
    /// facing away would be punished for it.
    public void pin(int ticks) {
        this.pinnedTicks = Math.max(this.pinnedTicks, ticks);
        markObserved(this.lastObserver);
    }

    public boolean isPinned() {
        return this.pinnedTicks > 0;
    }

    /// Reset to "being watched", the safe default — used on spawn and after a forced manifestation
    /// so a boss never begins life already entitled to vanish.
    public void reset() {
        this.lastAnswer.clear();
        this.observed = true;
        this.observedTicks = 0;
        this.unobservedTicks = 0;
        this.pinnedTicks = 0;
    }
}
