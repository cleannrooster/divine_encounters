package com.cleannrooster.divineencounters.shrine;

import com.cleannrooster.divineencounters.omen.OmenType;
import org.jetbrains.annotations.Nullable;

/// What a shrine decided, and why.
///
/// `UNANSWERED` is a first-class outcome rather than a failure. A prayer the shrine cannot resolve
/// is a legitimate result — the words were heard and found wanting — and it reads very differently
/// to the player than an error would.
///
/// @param kind   which of the outcomes occurred
/// @param omen   the omen to bind, when there is one
public record PrayerOutcome(Kind kind, @Nullable OmenType omen) {
    public enum Kind {
        /// The words leaned far enough, one way or the other, to name something.
        ANSWERED,
        /// Heard, but too divided or too faint to resolve.
        UNANSWERED
    }

    public static PrayerOutcome answered(OmenType omen) {
        return new PrayerOutcome(Kind.ANSWERED, omen);
    }

    public static PrayerOutcome unanswered() {
        return new PrayerOutcome(Kind.UNANSWERED, null);
    }

    public boolean isAnswered() {
        return this.kind == Kind.ANSWERED && this.omen != null;
    }
}
