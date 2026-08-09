package com.cleannrooster.divineencounters.shrine;

import java.util.List;
import java.util.Map;

/// What the shrine made of a prayer.
///
/// Two numbers per axis, and both matter:
///
/// - **disposition** (-1..+1) — which way the words lean. It is the *signed* total divided by the
///   *absolute* total, so it measures one-sidedness rather than volume. A prayer begging for both
///   victory and vengeance lands near zero however loudly it does so, which is correct: the shrine
///   cannot find a single answer in a divided prayer.
/// - **conviction** — how much recognised weight was there at all. This is what separates "the
///   shrine disagrees with you" from "the shrine heard nothing", and it is why two triumphant words
///   in an otherwise blank book do not summon a tower.
///
/// Requiring both means neither a long rambling prayer nor a terse conflicted one can force a
/// result, which is the property the whole feature rests on.
///
/// @param disposition per axis, -1 to +1
/// @param conviction  per axis, total absolute recognised weight
/// @param matches     every term that fired, in the order encountered, for debugging and tuning
public record PrayerReading(
        Map<DispositionAxis, Float> disposition,
        Map<DispositionAxis, Float> conviction,
        List<Match> matches
) {
    /// One firing of a term, with the weight it actually contributed after repetition decay.
    ///
    /// Keeping the effective weight alongside the nominal one is what makes the debug output
    /// useful: the interesting question when tuning is almost always "why did this count for so
    /// little", and the answer is usually that it was the fifth repeat.
    public record Match(PrayerTerm term, int occurrence, float contributed) {
    }

    public float dispositionOn(DispositionAxis axis) {
        return this.disposition.getOrDefault(axis, 0.0f);
    }

    public float convictionOn(DispositionAxis axis) {
        return this.conviction.getOrDefault(axis, 0.0f);
    }

    public boolean isEmpty() {
        return this.matches.isEmpty();
    }
}
