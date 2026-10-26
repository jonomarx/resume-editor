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
 * Finds vocabulary tags (by name or alias) in posting text. Pure and bounded: the text is normalized and cut at
 * {@link PostingText#MAX}, every pattern is a quoted literal between fixed lookarounds (no nested quantifiers), and nothing in
 * the text is executed.
 *
 * <p>{@link Mode#NAIVE} is the baseline (case-insensitive substring). {@link Mode#GUARDED} has word boundaries that understand
 * {@code C++}, {@code C#}, {@code .NET} and {@code Node.js}, ignores negated mentions ("Rust is not required"), and treats
 * ambiguous names (Go, R, C, Spring, Swift, Rust...) strictly: they count only inside the <em>ambiguous zone</em> (the title, a
 * requirement section or an explicit skills list), only written exactly like the vocabulary, only with a skill-like context
 * (a word like "experience with", or list punctuation next to another skill), never before a year or a calendar-like word
 * ("Spring 2027", "Spring break").
 */
public final class VocabularyTagMatcher {
    public enum Mode { NAIVE, GUARDED }

    public static final int MAX_TEXT = PostingText.MAX;
    private static final int MAX_TERM = 60;

    /** Names that are also ordinary words or fragments. */
    static final Set<String> AMBIGUOUS = Set.of("go", "r", "c", "d", "swift", "rust", "spring", "express", "ruby", "julia", "dart",
            "ember", "flask", "rails", "next", "nest", "spark", "hive", "solid", "ant", "make", "ray", "bash", "java script");

    /** A word that clearly introduces a skill. */
    private static final String STRONG = "programming|languages?|proficien\\w*|experience (?:in|with)|knowledge of|familiar\\w* with|fluen\\w*|"
            + "skills?(?: in| with)?|stack|written in|using|such as|including|e\\.g\\.|computing in|coding in|develop\\w* (?:in|with)|expertise (?:in|with)";
    private static final Pattern STRONG_BEFORE = Pattern.compile("(?i)(?:" + STRONG + ")[ :(]{0,3}$");
    private static final Pattern WEAK_BEFORE = Pattern.compile("(?i)(?:\\b(?:in|with|of|or|and)|[,/;(])\\s{0,2}$");
    private static final Pattern STRONG_AFTER = Pattern.compile("(?i)^\\s{0,2}(?:programming|language|developer|engineer|experience|skills?|framework)\\b");
    private static final Pattern WEAK_AFTER = Pattern.compile("^\\s{0,2}(?:[,/;)]|or\\s{1,2}\\p{Lu}|and\\s{1,2}\\p{Lu})");
    private static final Pattern NOT_A_SKILL_AFTER = Pattern.compile("(?i)^\\s{1,2}(?:20\\d\\d|break|time|season|semester|quarter|term|cleaning|fling|"
            + "to\\b|beyond|above|further|ahead|forward|live|big|the\\b)");
    private static final Pattern NEGATED_BEFORE = Pattern.compile("(?i)\\b(?:no|without|zero|never)\\s{1,2}(?:\\w+\\s{1,2}){0,2}$|\\bnot\\s{1,2}$");
    private static final Pattern NEGATED_AFTER = Pattern.compile("(?i)^(?:\\s{1,2}[\\w'-]{1,20}){0,3}?\\s{1,2}(?:(?:is|are|will be|would be)\\s{1,2})?not\\s{1,2}"
            + "(?:required|needed|necessary|expected|a\\s{1,2}requirement)\\b|^\\s{1,2}(?:is|are)\\s{1,2}not\\b");

    private record Term(long tagId, String text, boolean ambiguous, boolean letter, Pattern pattern) {}

    private final List<Term> terms = new ArrayList<>();

    public VocabularyTagMatcher(List<VocabularyTag> vocabulary) {
        this(vocabulary, null);
    }

    /** @param taxonomy extra aliases per tag from an open taxonomy (contender B), or null; the same guards apply to them */
    public VocabularyTagMatcher(List<VocabularyTag> vocabulary, TaxonomyAliases taxonomy) {
        // Every name and alias the user's vocabulary already has: a taxonomy alias equal to one of them is never added (it would
        // steal or duplicate it).
        Set<String> own = new java.util.HashSet<>();
        for (VocabularyTag t : vocabulary) {
            own.add(t.name().strip().toLowerCase(Locale.ROOT));
            for (String a : t.aliases()) own.add(a.strip().toLowerCase(Locale.ROOT));
        }
        for (VocabularyTag t : vocabulary) {
            addTerms(t.id(), t.name(), false);
            for (String a : t.aliases()) addTerms(t.id(), a, false);
            if (taxonomy != null) {
                for (String a : taxonomy.aliasesFor(t)) {
                    if (!own.contains(a.strip().toLowerCase(Locale.ROOT))) addTerms(t.id(), a, true);
                }
            }
        }
    }

    private void addTerms(long tagId, String n, boolean fromTaxonomy) {
        if (n == null) return;
        n = n.strip();
        if (n.isEmpty() || n.length() > MAX_TERM) return;
        boolean letter = n.length() == 1 && Character.isLetter(n.charAt(0));
        // A short single-word taxonomy alias ("node", "cake", "es") is as risky as a short ordinary word: same strict guards.
        boolean shortWord = fromTaxonomy && n.length() <= 4 && n.indexOf(' ') < 0 && n.chars().allMatch(Character::isLetter);
        boolean ambiguous = letter || shortWord || AMBIGUOUS.contains(n.toLowerCase(Locale.ROOT));
        terms.add(new Term(tagId, n, ambiguous, letter, boundary(n, ambiguous)));
    }

    /** Letters and digits on either side disqualify; so do a following + or # (C vs C++), and for ambiguous names a hyphen or ampersand. */
    private static Pattern boundary(String n, boolean ambiguous) {
        String body = Pattern.quote(n);
        char first = n.charAt(0);
        char last = n.charAt(n.length() - 1);
        String before = Character.isLetterOrDigit(first) ? "(?<![\\p{L}\\p{N}_.#+])" : "(?<![\\p{L}\\p{N}_])";
        String after = Character.isLetterOrDigit(last)
                ? "(?![\\p{L}\\p{N}_+#]|&\\p{L}|\\.\\p{L}" + (ambiguous ? "|-\\p{L}" : "") + ")" : "(?![\\p{L}\\p{N}_])";
        return Pattern.compile(before + body + after, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Matches with the whole text also serving as the ambiguous zone. */
    public Set<Long> match(String text, Mode mode) {
        return match(text, text, mode);
    }

    /**
     * @param text whole posting text: unambiguous names count anywhere in it
     * @param ambiguousZone the part where ambiguous names may count (title, requirement sections, skills lists); null or blank means none
     * @return ids of the tags found, in vocabulary order of first hit
     */
    public Set<Long> match(String text, String ambiguousZone, Mode mode) {
        Set<Long> found = new LinkedHashSet<>();
        text = PostingText.normalize(text);
        if (text.isBlank()) return found;
        String zone = ambiguousZone == null ? "" : PostingText.normalize(ambiguousZone);
        String lower = mode == Mode.NAIVE ? text.toLowerCase(Locale.ROOT) : null;
        for (Term t : terms) {
            if (found.contains(t.tagId)) continue;
            if (mode == Mode.NAIVE) {
                if (lower.contains(t.text.toLowerCase(Locale.ROOT))) found.add(t.tagId);
                continue;
            }
            String where = t.ambiguous ? zone : text;
            Matcher m = t.pattern.matcher(where);
            while (m.find()) {
                if (negated(where, m.start(), m.end())) continue;
                if (!t.ambiguous || skillLike(where, m.start(), m.end(), t)) {
                    found.add(t.tagId);
                    break;
                }
            }
        }
        return found;
    }

    private static boolean negated(String text, int start, int end) {
        String before = text.substring(Math.max(0, start - 24), start);
        int nl = before.lastIndexOf('\n');
        if (nl >= 0) before = before.substring(nl + 1);
        String after = text.substring(end, Math.min(text.length(), end + 40));
        nl = after.indexOf('\n');
        if (nl >= 0) after = after.substring(0, nl);
        return NEGATED_BEFORE.matcher(before).find() || NEGATED_AFTER.matcher(after).find();
    }

    private static boolean skillLike(String text, int start, int end, Term t) {
        if (!text.regionMatches(start, t.text, 0, t.text.length())) return false; // exact case only
        String before = text.substring(Math.max(0, start - 30), start);
        String after = text.substring(end, Math.min(text.length(), end + 20));
        if (NOT_A_SKILL_AFTER.matcher(after).find()) return false;
        if (STRONG_BEFORE.matcher(before).find() || STRONG_AFTER.matcher(after).find()) return true;
        if (t.letter) return false; // C, R, D need a clear skill word
        return WEAK_BEFORE.matcher(before).find() || WEAK_AFTER.matcher(after).find();
    }
}
