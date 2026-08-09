package com.cleannrooster.divineencounters.shrine;

/// One recognised word or phrase, and what it says.
///
/// A term is deliberately flat data — text, axis, weight — so the lexicon can eventually be loaded
/// from JSON without any of the scoring code learning a new shape.
///
/// @param text   the literal, already normalised; a phrase keeps its single spaces
/// @param axis   which dimension it speaks to
/// @param weight signed contribution: negative toward the axis's negative pole, positive toward the
///               positive one. Magnitude is conviction, not certainty — `vengeance` should outweigh
///               `unkind`.
public record PrayerTerm(String text, DispositionAxis axis, float weight) {
    /// How many words the term spans. Phrases are matched before single words, longest first.
    public int wordCount() {
        var count = 1;
        for (var i = 0; i < this.text.length(); i++) {
            if (this.text.charAt(i) == ' ') {
                count++;
            }
        }
        return count;
    }

    public boolean isPhrase() {
        return wordCount() > 1;
    }
}
