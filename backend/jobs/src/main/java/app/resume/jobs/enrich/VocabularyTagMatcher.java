package app.resume.jobs.enrich;

import app.resume.vocabulary.VocabularyTag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds vocabulary tags (by name or alias) in posting text. Pure and bounded: the text is cut at {@link #MAX_TEXT}, every
 * pattern is a quoted literal between fixed lookarounds (no nested quantifiers), and nothing in the text is executed.
 *
 * <p>Two modes, compared in docs/decision-003: {@link Mode#NAIVE} (case-insensitive substring, the baseline) and
 * {@link Mode#GUARDED} (word boundaries that understand {@code C++}, {@code C#}, {@code .NET} and {@code Node.js}; ambiguous
 * names such as Go, R and C only count when written exactly like the vocabulary and used in a skill-like context).
 */
public final class VocabularyTagMatcher {
    public enum Mode { NAIVE, GUARDED }

    public static final int MAX_TEXT = 20_000;
    private static final int MAX_TERM = 60;

    /** Names that are also ordinary words or fragments; they need an exact-case match plus a skill cue. */
    static final Set<String> AMBIGUOUS = Set.of("go", "r", "c", "d", "swift", "rust", "spring", "express", "ruby", "julia", "dart",
            "ember", "flask", "rails", "next", "nest", "spark", "hive", "solid", "ant", "make", "ray", "bash", "java script");

    private static final Pattern CUE_BEFORE = Pattern.compile("(?i)(programming|languages?|proficien\\w*|experience (in|with)|knowledge of|"
            + "familiar\\w* with|fluen\\w*|skills?|stack|written in|using|such as|including|e\\.g\\.|\\b(in|with|or|and|of)|[,/;:(•*-])\\s*$");
    private static final Pattern CUE_AFTER = Pattern.compile("(?i)^\\s*(programming|language|developer|engineer|experience|skills?|"
            + "framework|or\\s+\\p{Lu}|and\\s+\\p{Lu}|[,/;)•])");

    private record Term(long tagId, String text, boolean ambiguous, Pattern pattern) {}

    private final List<Term> terms = new ArrayList<>();

    public VocabularyTagMatcher(List<VocabularyTag> vocabulary) {
        for (VocabularyTag t : vocabulary) {
            List<String> names = new ArrayList<>();
            names.add(t.name());
            names.addAll(t.aliases());
            for (String n : names) {
                if (n == null) continue;
                n = n.strip();
                if (n.isEmpty() || n.length() > MAX_TERM) continue;
                boolean ambiguous = n.length() <= 2 && Character.isLetter(n.charAt(0)) || AMBIGUOUS.contains(n.toLowerCase(Locale.ROOT));
                terms.add(new Term(t.id(), n, ambiguous, boundary(n, ambiguous)));
            }
        }
    }

    /** Letters and digits on either side disqualify; so do a following + or # (C vs C++), and for ambiguous names a hyphen or ampersand. */
    private static Pattern boundary(String n, boolean ambiguous) {
        String body = Pattern.quote(n);
        char first = n.charAt(0);
        char last = n.charAt(n.length() - 1);
        String before = Character.isLetterOrDigit(first) ? "(?<![\\p{L}\\p{N}_.#+])" : "(?<![\\p{L}\\p{N}_])";
        // A name that ends in a letter or digit must not be followed by more of a token: C is not C++, C#, C.x or R&D.
        String after = Character.isLetterOrDigit(last) ? "(?![\\p{L}\\p{N}_+#]|&\\p{L}|\\.\\p{L}" + (ambiguous ? "|-\\p{L}" : "") + ")" : "(?![\\p{L}\\p{N}_])";
        return Pattern.compile(before + body + after, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Ids of the tags found in the text, in vocabulary order of first hit. */
    public Set<Long> match(String text, Mode mode) {
        Set<Long> found = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return found;
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        for (Term t : terms) {
            if (found.contains(t.tagId)) continue;
            if (mode == Mode.NAIVE) {
                if (text.toLowerCase(Locale.ROOT).contains(t.text.toLowerCase(Locale.ROOT))) found.add(t.tagId);
                continue;
            }
            Matcher m = t.pattern.matcher(text);
            while (m.find()) {
                if (!t.ambiguous || skillLike(text, m.start(), m.end(), t.text)) {
                    found.add(t.tagId);
                    break;
                }
            }
        }
        return found;
    }

    private static boolean skillLike(String text, int start, int end, String term) {
        if (!text.regionMatches(start, term, 0, term.length())) return false; // exact case only
        String before = text.substring(Math.max(0, start - 30), start);
        String after = text.substring(end, Math.min(text.length(), end + 20));
        return CUE_BEFORE.matcher(before).find() || CUE_AFTER.matcher(after).find();
    }
}
