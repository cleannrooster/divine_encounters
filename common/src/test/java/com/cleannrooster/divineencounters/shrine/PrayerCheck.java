package com.cleannrooster.divineencounters.shrine;

import com.cleannrooster.divineencounters.omen.DivineOmens;

/// Standalone verification of the shrine's prayer interpretation, run from the `prayerCheck`
/// Gradle task. Lives in the test source set, so it never ships.
///
/// The property this exists to defend is **exploit resistance**. Everything else here is a
/// convenience; the repetition tests are the point. If `hate hate hate hate hate hate hate hate`
/// ever out-scores a genuinely written prayer, the whole feature collapses into a password prompt,
/// and that regression is silent — the code still compiles and the shrine still answers.
public final class PrayerCheck {
    private static int failures;

    public static void main(String[] args) {
        checkNormalisation();
        checkStemming();
        checkRepetitionResistance();
        checkPhrases();
        checkThresholds();
        checkOutcomes();

        System.out.println(failures == 0 ? "ALL PRAYER CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static PrayerReading read(String text) {
        return DivinePrayers.evaluator().evaluate(text);
    }

    private static float conviction(String text) {
        return read(text).convictionOn(DispositionAxis.SPIRIT);
    }

    private static float disposition(String text) {
        return read(text).dispositionOn(DispositionAxis.SPIRIT);
    }

    /// Punctuation, case and formatting codes must not change what a prayer means.
    private static void checkNormalisation() {
        expect("case is ignored", disposition("VICTORY GLORY TRIUMPH") > 0.9f);
        expect("punctuation is stripped",
                Math.abs(conviction("victory, glory! triumph.") - conviction("victory glory triumph"))
                        < 0.01f);
        expect("formatting codes are not prayer content",
                Math.abs(conviction("§cvictory §lglory") - conviction("victory glory"))
                        < 0.01f);
        expect("apostrophes do not split words",
                PrayerText.tokenise("victor's").size() == 1);
        // Pages are joined with a separator; without it the last and first words would fuse.
        expect("page breaks do not fuse words",
                PrayerText.tokenise("glory\nvictory").size() == 2);
        expect("empty text reads as nothing", read("").isEmpty());
        expect("unrelated words contribute nothing",
                conviction("the quick brown fox jumps over the lazy dog") == 0.0f);
    }

    /// Grammatical variants should reach their root without a separate entry each.
    private static void checkStemming() {
        expect("conquering reaches conquer", conviction("conquering") > 0.0f);
        expect("conquered reaches conquer", conviction("conquered") > 0.0f);
        expect("betrayed reaches betray", conviction("betrayed") > 0.0f);
        // The stem floor exists so short words are not destroyed: `hated` must not become `hat`.
        expect("short words are not over-stemmed", PrayerText.stem("hated").equals("hated")
                || PrayerText.stem("hated").equals("hate"));
        expect("hated still reads as malicious", disposition("hated hatred spite") < -0.9f);
    }

    /// The exploit test. Repetition must not substitute for meaning.
    private static void checkRepetitionResistance() {
        var spammed = conviction("hate hate hate hate hate hate hate hate hate hate "
                + "hate hate hate hate hate hate hate hate hate hate");
        var single = conviction("hate");
        expect("twenty repeats are worth less than three separate uses", spammed < single * 3.0f);

        var genuine = conviction("malice vengeance betrayal torment");
        expect("a written prayer beats a spammed word", genuine > spammed);

        var hundred = conviction("hate ".repeat(100));
        expect("a hundred repeats add nothing over twenty",
                Math.abs(hundred - spammed) < 0.01f);
        expect("repetition still crosses no threshold on its own",
                !PrayerOutcomeResolver.resolve(read("hate ".repeat(100))).isAnswered()
                        || genuine > hundred);

        // Triumphant side must be protected identically.
        var spammedGlory = conviction("glory ".repeat(50));
        expect("triumphant spam is capped too", spammedGlory < conviction("glory") * 3.0f);

        // Phrases decay on the same schedule as words.
        var phraseSpam = conviction("no mercy ".repeat(20));
        expect("phrase spam is capped", phraseSpam < conviction("no mercy") * 3.0f);
    }

    /// Phrases carry their own weight and consume their words.
    private static void checkPhrases() {
        var phrase = conviction("no mercy");
        expect("a phrase is recognised", phrase > 0.0f);
        expect("a phrase outweighs its parts scoring separately",
                phrase > conviction("mercy") + conviction("no"));
        expect("a phrase is malicious", disposition("no mercy") < 0.0f);
        expect("meet me in battle is triumphant", disposition("meet me in battle") > 0.0f);
        // Consuming the words is what makes phrase weights predictable.
        var matches = read("no mercy").matches();
        expect("a matched phrase fires exactly once", matches.size() == 1);
        expect("the match is the phrase, not a word", matches.get(0).term().isPhrase());
    }

    /// Both gates, and the difference between them.
    private static void checkThresholds() {
        // Faint: one-sided but barely anything said.
        var faint = read("brave");
        expect("a single word is too faint",
                faint.convictionOn(DispositionAxis.SPIRIT) < DivinePrayers.CONVICTION_THRESHOLD);
        expect("a single word is unanswered", !PrayerOutcomeResolver.resolve(faint).isAnswered());
        expect("faint failure is reported as such",
                PrayerOutcomeResolver.failureReason(faint).equals("too_faint"));

        // Divided: plenty said, but pulling both ways.
        var divided = read("glory victory triumph malice hatred vengeance");
        expect("a divided prayer has conviction",
                divided.convictionOn(DispositionAxis.SPIRIT) >= DivinePrayers.CONVICTION_THRESHOLD);
        expect("a divided prayer sits near neutral",
                Math.abs(divided.dispositionOn(DispositionAxis.SPIRIT))
                        < DivinePrayers.DISPOSITION_THRESHOLD);
        expect("a divided prayer is unanswered",
                !PrayerOutcomeResolver.resolve(divided).isAnswered());
        expect("divided failure is reported as such",
                PrayerOutcomeResolver.failureReason(divided).equals("too_divided"));

        expect("nothing recognised is reported distinctly",
                PrayerOutcomeResolver.failureReason(read("bread and cheese"))
                        .equals("nothing_recognised"));

        // Volume alone must not overcome division.
        var loudDivided = read("glory ".repeat(10) + "malice ".repeat(10));
        expect("shouting both sides still resolves to nothing",
                !PrayerOutcomeResolver.resolve(loudDivided).isAnswered());
    }

    /// End to end: a prayer a player might plausibly write reaches the right omen.
    private static void checkOutcomes() {
        var triumphant = read("I ask only for a worthy foe. Grant me the courage to stand, "
                + "the strength to endure, and glory in victory.");
        var warOutcome = PrayerOutcomeResolver.resolve(triumphant);
        expect("a triumphant prayer is answered", warOutcome.isAnswered());
        expect("a triumphant prayer binds War", warOutcome.omen() == DivineOmens.OMEN_OF_WAR);

        var malicious = read("Let them know suffering. I want vengeance for the betrayal, "
                + "and no mercy for any of them. Ruin everything they built.");
        var maliceOutcome = PrayerOutcomeResolver.resolve(malicious);
        expect("a malicious prayer is answered", maliceOutcome.isAnswered());
        expect("a malicious prayer binds Malice", maliceOutcome.omen() == DivineOmens.OMEN_OF_MALICE);

        // A short but genuine prayer should still work — the feature is writing, not word count.
        var terse = PrayerOutcomeResolver.resolve(read("glory and victory"));
        expect("a short genuine prayer still answers", terse.isAnswered());

        expect("the lexicon carries both poles",
                DivinePrayers.lexicon().all().stream().anyMatch(term -> term.weight() > 0)
                        && DivinePrayers.lexicon().all().stream().anyMatch(term -> term.weight() < 0));
        System.out.println("  lexicon holds " + DivinePrayers.lexicon().size() + " terms");
    }

    private static void expect(String what, boolean condition) {
        if (!condition) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    private PrayerCheck() {
    }
}
