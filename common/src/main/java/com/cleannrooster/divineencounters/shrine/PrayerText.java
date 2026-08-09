package com.cleannrooster.divineencounters.shrine;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Turns a written book into a clean stream of words.
///
/// Everything here is deliberately dull and mechanical. The interesting judgement happens in
/// {@link PrayerEvaluator}; this stage only has to make sure the evaluator sees what the player
/// *wrote* rather than what Minecraft stored.
///
/// The pitfalls it exists to handle:
/// - pages are `Component`s, not strings, so styling and translation wrappers have to be flattened
///   away or a colour code would count as prayer content;
/// - a book's pages are separate strings, and joining them without a separator would fuse the last
///   word of one page to the first of the next;
/// - only signed books have body text worth reading — an unsigned draft is not a prayer.
public final class PrayerText {
    /// Suffixes stripped when reducing a word to its stem. Ordered longest-first so `conquering`
    /// loses `ing` rather than being mangled by a shorter match.
    private static final String[] SUFFIXES = {"ings", "ing", "edly", "ed", "es", "s"};
    /// A stem shorter than this is meaningless — stripping `ed` from `hated` must not yield `hat`.
    private static final int MIN_STEM = 4;

    private PrayerText() {
    }

    /// The body text of a signed book, or empty when the stack is not one.
    ///
    /// Title and author are deliberately excluded: a player naming their book "Victory" should not
    /// have that outweigh what they actually wrote, and an author name is not a prayer at all.
    public static String bodyOf(ItemStack stack) {
        var content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) {
            return "";
        }
        var joined = new StringBuilder();
        for (var page : content.pages()) {
            // raw() rather than the filtered view: the shrine reads what was written, and chat
            // filtering is not the shrine's business.
            var text = page.raw().getString();
            if (text.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                // Pages break mid-sentence constantly; without this, two words fuse into a
                // nonsense token at every page boundary.
                joined.append('\n');
            }
            joined.append(text);
        }
        return joined.toString();
    }

    /// Whether a stack is a signed book with something in it.
    public static boolean isSignedBook(ItemStack stack) {
        return stack.has(DataComponents.WRITTEN_BOOK_CONTENT) && !bodyOf(stack).isBlank();
    }

    /// Lowercase, strip formatting codes and punctuation, collapse whitespace.
    public static String normalise(String raw) {
        var lower = raw.toLowerCase(Locale.ROOT);
        var out = new StringBuilder(lower.length());
        for (var i = 0; i < lower.length(); i++) {
            var c = lower.charAt(i);
            // Legacy formatting codes ride inside the text as a section sign plus one character.
            if (c == '§') {
                i++;
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == '\'') {
                // Keep apostrophes out entirely so `victor's` matches `victors`.
                continue;
            } else {
                out.append(' ');
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    /// Split normalised text into words.
    public static List<String> tokenise(String raw) {
        var normalised = normalise(raw);
        if (normalised.isEmpty()) {
            return List.of();
        }
        var tokens = new ArrayList<String>();
        for (var token : normalised.split(" ")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /// Reduce a word to a crude stem so grammatical variants collapse together.
    ///
    /// This is intentionally the simplest thing that works. A real stemmer would be a liability
    /// here: the vocabulary is small and curated, so anything it gets wrong is easier to fix by
    /// adding the literal word than by tuning an algorithm.
    public static String stem(String word) {
        for (var suffix : SUFFIXES) {
            if (word.length() - suffix.length() >= MIN_STEM && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }
}
