package com.cleannrooster.divineencounters.shrine;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The vocabulary a shrine understands.
///
/// This is a controlled vocabulary, not language understanding, and it should stay that way. The
/// shrine is not trying to comprehend a prayer — it is listening for recurring themes and weighing
/// which way they lean.
///
/// Two lookup paths, because players do not write in dictionary forms:
/// - **literal**, for exactly the word as authored;
/// - **stemmed**, so `conquering` and `conquered` reach `conquer` without three separate entries.
///
/// Phrases are indexed by word count so the matcher can try the longest first. A phrase carries its
/// own weight rather than the sum of its parts, which is what lets `no mercy` mean something its
/// two words do not.
///
/// Everything here is loaded through {@link #add}, so a future JSON-driven lexicon only has to call
/// the same method — nothing downstream knows where terms came from.
public final class PrayerLexicon {
    /// Longest phrase the matcher will look for. Bounds the n-gram window.
    public static final int MAX_PHRASE_WORDS = 4;

    private final Map<String, PrayerTerm> literal = new HashMap<>();
    private final Map<String, PrayerTerm> stemmed = new HashMap<>();
    /// Phrases bucketed by word count, so lookup can walk from longest to shortest.
    private final Map<Integer, Map<String, PrayerTerm>> phrases = new HashMap<>();
    private int longestPhrase = 1;

    /// Add a term. Later additions win, so a datapack can override a built-in weight.
    public PrayerLexicon add(PrayerTerm term) {
        var words = term.wordCount();
        if (words > 1) {
            this.phrases.computeIfAbsent(Math.min(words, MAX_PHRASE_WORDS), key -> new HashMap<>())
                    .put(term.text(), term);
            this.longestPhrase = Math.max(this.longestPhrase, Math.min(words, MAX_PHRASE_WORDS));
            return this;
        }
        this.literal.put(term.text(), term);
        // Registering the stem too is what makes grammatical variants work without listing them.
        // Never let a stem shadow a literal entry — an explicit word always wins.
        var stem = PrayerText.stem(term.text());
        if (!stem.equals(term.text())) {
            this.stemmed.putIfAbsent(stem, term);
        }
        this.stemmed.putIfAbsent(term.text(), term);
        return this;
    }

    public PrayerLexicon add(String text, DispositionAxis axis, float weight) {
        return add(new PrayerTerm(PrayerText.normalise(text), axis, weight));
    }

    /// Convenience for declaring a run of terms that share an axis and weight.
    public PrayerLexicon addAll(DispositionAxis axis, float weight, String... terms) {
        for (var term : terms) {
            add(term, axis, weight);
        }
        return this;
    }

    /// Look up a single token: exact form first, then its stem.
    public @Nullable PrayerTerm lookup(String token) {
        var exact = this.literal.get(token);
        if (exact != null) {
            return exact;
        }
        return this.stemmed.get(PrayerText.stem(token));
    }

    /// Look up a phrase of exactly `words` tokens.
    public @Nullable PrayerTerm lookupPhrase(String joined, int words) {
        var bucket = this.phrases.get(words);
        return bucket == null ? null : bucket.get(joined);
    }

    public int longestPhrase() {
        return this.longestPhrase;
    }

    public int size() {
        var total = this.literal.size();
        for (var bucket : this.phrases.values()) {
            total += bucket.size();
        }
        return total;
    }

    /// Every term, for debug output and for tests that want to check the vocabulary is balanced.
    public List<PrayerTerm> all() {
        var out = new ArrayList<>(this.literal.values());
        for (var bucket : this.phrases.values()) {
            out.addAll(bucket.values());
        }
        return out;
    }
}
