package com.cleannrooster.divineencounters.shrine;

import com.cleannrooster.divineencounters.omen.DivineOmens;

/// Maps a reading onto a shrine result.
///
/// Deliberately the only place that knows a positive spirit means War and a negative one means
/// Malice. Everything upstream deals in axes and numbers, so adding a third Visage — or an outcome
/// that is not an omen at all — is a change here and nowhere else.
///
/// Both gates must pass. A prayer can fail by being **divided** (it wants victory *and* vengeance)
/// or by being **faint** (it barely says anything the shrine knows), and those are genuinely
/// different failures even though they produce the same silence.
public final class PrayerOutcomeResolver {
    private PrayerOutcomeResolver() {
    }

    public static PrayerOutcome resolve(PrayerReading reading) {
        var axis = DispositionAxis.SPIRIT;
        var disposition = reading.dispositionOn(axis);
        var conviction = reading.convictionOn(axis);

        if (conviction < DivinePrayers.CONVICTION_THRESHOLD) {
            return PrayerOutcome.unanswered();
        }
        if (disposition >= DivinePrayers.DISPOSITION_THRESHOLD) {
            return PrayerOutcome.answered(DivineOmens.OMEN_OF_WAR);
        }
        if (disposition <= -DivinePrayers.DISPOSITION_THRESHOLD) {
            return PrayerOutcome.answered(DivineOmens.OMEN_OF_MALICE);
        }
        return PrayerOutcome.unanswered();
    }

    /// Which gate a prayer failed, for feedback and debugging. Only meaningful when unanswered.
    public static String failureReason(PrayerReading reading) {
        var axis = DispositionAxis.SPIRIT;
        if (reading.convictionOn(axis) < DivinePrayers.CONVICTION_THRESHOLD) {
            return reading.isEmpty() ? "nothing_recognised" : "too_faint";
        }
        return "too_divided";
    }
}
