package com.cleannrooster.divineencounters.shrine;

/// The shipped vocabulary and the thresholds a prayer must clear.
///
/// Weights are a rough three-tier scale — 1.0 for a word that merely leans, 1.5 for one that
/// commits, 2.0 for one that leaves no doubt. Phrases sit above their parts because choosing to
/// write them is a stronger signal than the words landing near each other by chance.
///
/// This is the file to edit when tuning. Everything else in the package is mechanism.
public final class DivinePrayers {
    private DivinePrayers() {
    }

    // --- thresholds ---------------------------------------------------------------------------------

    /// How one-sided a prayer must be before the shrine will name it. Below this the words are
    /// too divided to resolve, however many of them there are.
    public static final float DISPOSITION_THRESHOLD = 0.45f;

    /// How much recognised weight a prayer needs before it counts as a prayer at all. Roughly two
    /// committed words, or three mild ones — enough that a single word cannot summon anything, and
    /// low enough that a genuinely short prayer still works.
    public static final float CONVICTION_THRESHOLD = 2.5f;

    // --- the lexicon --------------------------------------------------------------------------------

    private static final PrayerLexicon LEXICON = build();

    public static PrayerLexicon lexicon() {
        return LEXICON;
    }

    public static PrayerEvaluator evaluator() {
        return new PrayerEvaluator(LEXICON);
    }

    private static PrayerLexicon build() {
        var lexicon = new PrayerLexicon();
        var spirit = DispositionAxis.SPIRIT;

        // --- triumphant: the language of a fair fight sought and won -----------------------------
        lexicon.addAll(spirit, 2.0f,
                "triumph", "triumphant", "victory", "victorious", "glory", "glorious",
                "conquer", "conquest", "champion", "valor", "valour");
        lexicon.addAll(spirit, 1.5f,
                "courage", "honor", "honour", "prevail", "overcome", "worthy", "endure",
                "strength", "brave", "bravery", "steadfast", "resolve", "renown", "unbroken");
        lexicon.addAll(spirit, 1.0f,
                "challenge", "contest", "duel", "trial", "test", "strive", "stand", "rise",
                "banner", "shield", "proud", "noble", "just", "fair", "earn", "merit");

        // Phrases weigh more than their parts: writing one is a choice, not a coincidence.
        lexicon.add("face me", spirit, 2.5f);
        lexicon.add("meet me in battle", spirit, 3.0f);
        lexicon.add("prove myself", spirit, 2.5f);
        lexicon.add("worthy foe", spirit, 2.5f);
        lexicon.add("glorious death", spirit, 2.5f);
        lexicon.add("no retreat", spirit, 2.0f);

        // --- malicious: the language of a grudge, not a challenge --------------------------------
        lexicon.addAll(spirit, -2.0f,
                "malice", "hatred", "hate", "vengeance", "revenge", "spite", "cruelty",
                "torment", "betrayal", "betray", "despair", "ruin");
        lexicon.addAll(spirit, -1.5f,
                "suffer", "suffering", "curse", "cursed", "destroy", "destruction", "wrath",
                "loathe", "venom", "rot", "grudge", "punish", "agony", "dread", "spiteful");
        lexicon.addAll(spirit, -1.0f,
                "dark", "darkness", "shadow", "cold", "silence", "hollow", "bitter", "envy",
                "creep", "stalk", "hunt", "prey", "unseen", "whisper", "forgotten", "buried");

        lexicon.add("no mercy", spirit, -2.5f);
        lexicon.add("make them suffer", spirit, -3.0f);
        lexicon.add("from the dark", spirit, -2.0f);
        lexicon.add("i will not forgive", spirit, -3.0f);
        lexicon.add("take everything", spirit, -2.5f);
        lexicon.add("never forgive", spirit, -2.5f);

        return lexicon;
    }
}
