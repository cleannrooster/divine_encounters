package com.cleannrooster.divineencounters.shrine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Reads a prayer against a lexicon and produces a {@link PrayerReading}.
///
/// ### Repetition resistance
/// The single most important property here is that **writing one word two hundred times must not
/// work**. A player should be rewarded for meaning it, not for spamming it.
///
/// Each additional occurrence of the same term contributes a geometrically decaying share:
/// `1, 0.5, 0.25, ...`. That series converges to twice a single use, so the entire infinite
/// repetition of `hate` is worth less than two distinct malicious words, and a hard occurrence cap
/// stops the tail costing anything to compute. Phrases decay on the same schedule, tracked
/// separately from their component words.
///
/// The effect is that a prayer's strength comes from its *vocabulary* — how many different things
/// it says, and how strongly — rather than its length.
public final class PrayerEvaluator {
    /// Share contributed by each successive repeat of the same term.
    private static final float REPEAT_DECAY = 0.5f;
    /// Occurrences past this contribute nothing at all. The decayed value here is already ~3% of a
    /// single use, so the cap costs no expressiveness and bounds the work.
    private static final int MAX_OCCURRENCES = 6;

    private final PrayerLexicon lexicon;

    public PrayerEvaluator(PrayerLexicon lexicon) {
        this.lexicon = lexicon;
    }

    public PrayerReading evaluate(String rawText) {
        return evaluate(PrayerText.tokenise(rawText));
    }

    public PrayerReading evaluate(List<String> tokens) {
        var signed = new HashMap<DispositionAxis, Float>();
        var absolute = new HashMap<DispositionAxis, Float>();
        var matches = new ArrayList<PrayerReading.Match>();
        var occurrences = new HashMap<String, Integer>();

        var index = 0;
        while (index < tokens.size()) {
            // Longest phrase first, so `no mercy` is heard as itself rather than as two words that
            // happen to sit together.
            var phraseLength = matchPhrase(tokens, index, signed, absolute, matches, occurrences);
            if (phraseLength > 0) {
                index += phraseLength;
                continue;
            }
            var term = this.lexicon.lookup(tokens.get(index));
            if (term != null) {
                record(term, signed, absolute, matches, occurrences);
            }
            index++;
        }

        return new PrayerReading(normalise(signed, absolute), Map.copyOf(absolute), List.copyOf(matches));
    }

    /// Try to consume a phrase starting at `index`. Returns how many tokens it swallowed, or 0.
    ///
    /// A matched phrase consumes its words so they cannot also score individually — otherwise a
    /// phrase would always be worth its own weight *plus* its parts, and phrase weights would be
    /// impossible to reason about.
    private int matchPhrase(List<String> tokens, int index,
                            Map<DispositionAxis, Float> signed,
                            Map<DispositionAxis, Float> absolute,
                            List<PrayerReading.Match> matches,
                            Map<String, Integer> occurrences) {
        var longest = Math.min(this.lexicon.longestPhrase(), PrayerLexicon.MAX_PHRASE_WORDS);
        for (var length = longest; length >= 2; length--) {
            if (index + length > tokens.size()) {
                continue;
            }
            var joined = String.join(" ", tokens.subList(index, index + length));
            var term = this.lexicon.lookupPhrase(joined, length);
            if (term != null) {
                record(term, signed, absolute, matches, occurrences);
                return length;
            }
        }
        return 0;
    }

    private void record(PrayerTerm term, Map<DispositionAxis, Float> signed,
                        Map<DispositionAxis, Float> absolute, List<PrayerReading.Match> matches,
                        Map<String, Integer> occurrences) {
        var seen = occurrences.merge(term.text(), 1, Integer::sum);
        if (seen > MAX_OCCURRENCES) {
            return;
        }
        var share = (float) Math.pow(REPEAT_DECAY, seen - 1);
        var contributed = term.weight() * share;

        signed.merge(term.axis(), contributed, Float::sum);
        absolute.merge(term.axis(), Math.abs(contributed), Float::sum);
        matches.add(new PrayerReading.Match(term, seen, contributed));
    }

    /// Signed total over absolute total, per axis: how one-sided the prayer is, independent of how
    /// much was said.
    private static Map<DispositionAxis, Float> normalise(Map<DispositionAxis, Float> signed,
                                                        Map<DispositionAxis, Float> absolute) {
        var out = new HashMap<DispositionAxis, Float>();
        signed.forEach((axis, total) -> {
            var magnitude = absolute.getOrDefault(axis, 0.0f);
            out.put(axis, magnitude <= 1.0e-6f ? 0.0f
                    : Math.max(-1.0f, Math.min(1.0f, total / magnitude)));
        });
        return Map.copyOf(out);
    }
}
