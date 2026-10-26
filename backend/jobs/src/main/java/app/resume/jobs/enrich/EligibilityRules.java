package app.resume.jobs.enrich;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern rules for season, class year and work authorization. Season and class year return the matched phrase; work
 * authorization returns a normalized value (see {@link #workAuthorization}). Null means the text does not say; nothing is
 * guessed. All input is normalized and capped first and every repetition below is bounded, so cost stays linear and no rule
 * can recurse deeply on hostile text.
 */
public final class EligibilityRules {
    private EligibilityRules() {}

    public static final String SPONSORS = "sponsors";
    public static final String DOES_NOT_SPONSOR = "does_not_sponsor";
    public static final String AUTHORIZATION_REQUIRED = "authorization_required";

    private static final Pattern SEASON = Pattern.compile("(?i)\\b(summer|fall|autumn|winter|spring)\\s{1,3}(?:of\\s{1,3})?(20\\d\\d)\\b");
    private static final Pattern PAST_BEFORE = Pattern.compile("(?i)\\b(last|previous|past|since|until|during|every|each)\\s{1,3}$");
    private static final String YEAR_WORD = "(?:freshmen|freshman|sophomores?|juniors?|seniors?)";
    private static final String SEP = "\\s{0,3}(?:,|or|and|/)\\s{0,3}";
    private static final String MONTH = "(?:(?:spring|summer|fall|winter|december|may|june)\\s{1,3})?";
    private static final Pattern CLASS_YEAR = Pattern.compile("(?i)\\b(?:"
            + "rising\\s{1,3}" + YEAR_WORD + "(?:" + SEP + "(?:rising\\s{1,3})?" + YEAR_WORD + "){0,3}"
            + "|class\\s{1,3}of\\s{1,3}20\\d\\d"
            + "|(?:expected\\s{1,3})?graduat(?:ing|ion)(?:\\s{1,3}date)?(?:\\s{1,3}(?:in|on|by|between|of)|\\s{0,3}:)?\\s{0,3}" + MONTH + "(?:of\\s{1,3})?20\\d\\d(?:\\s{1,3}(?:-|to|or|and)\\s{1,3}20\\d\\d)?"
            + "|" + YEAR_WORD + SEP + YEAR_WORD
            + ")\\b");
    private static final Pattern SPONSOR = Pattern.compile("(?i)\\bsponsor\\w{0,8}");
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:no|not|cannot|can't|unable|unavailable|without|never|don't|doesn't|won't)\\b|n't\\b");
    private static final Pattern NOT_REQUIRED = Pattern.compile("(?i)\\bnot\\s{1,3}(?:required|needed|necessary)\\b");
    private static final Pattern AUTH = Pattern.compile("(?i)(authori[sz]ed\\s{1,3}to\\s{1,3}work|work\\s{1,3}authori[sz]ation|eligible\\s{1,3}to\\s{1,3}work|"
            + "legally\\s{1,3}(?:authori[sz]ed|entitled)\\s{1,3}to\\s{1,3}work|right\\s{1,3}to\\s{1,3}work|u\\.?s\\.?\\s{1,3}citizen|citizenship|"
            + "permanent\\s{1,3}resident|green\\s{1,3}card)");

    /** The first target season: "Summer 2027". Mentions after "last", "since", "in" and so on are about other times and are skipped. */
    public static String season(String text) {
        text = PostingText.normalize(text);
        Matcher m = SEASON.matcher(text);
        while (m.find()) {
            String before = text.substring(Math.max(0, m.start() - 12), m.start());
            if (PAST_BEFORE.matcher(before).find()) continue;
            String s = m.group(1);
            return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT) + " " + m.group(2);
        }
        return null;
    }

    public static String classYear(String text) {
        Matcher m = CLASS_YEAR.matcher(PostingText.normalize(text));
        if (!m.find()) return null;
        String phrase = m.group().replaceAll("\\s+", " ").strip();
        if (phrase.regionMatches(true, 0, "expected ", 0, 9)) phrase = phrase.substring(9); // "graduation date 2027" says it too
        return clip(phrase, 40);
    }

    /**
     * What the posting says about work authorization and visa sponsorship, as one of {@code does_not_sponsor},
     * {@code sponsors}, {@code authorization_required}, or null when it says nothing. Sentences are judged separately;
     * "does not sponsor" wins over "sponsors", which wins over "authorization required". Security clearance is not
     * work authorization and is ignored. (The LLM enricher stores a free-text phrase in the same column.)
     */
    public static String workAuthorization(String text) {
        text = PostingText.normalize(text);
        boolean no = false, yes = false, req = false;
        int from = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean end = i == text.length() || text.charAt(i) == '\n'
                    || text.charAt(i) == '.' && (i + 1 >= text.length() || text.charAt(i + 1) == ' ' && !isInitialism(text, i))
                    || text.charAt(i) == ';';
            if (!end) continue;
            String sentence = text.substring(from, i);
            from = i + 1;
            if (sentence.isEmpty() || sentence.length() > 400) continue;
            Matcher sp = SPONSOR.matcher(sentence);
            if (sp.find()) {
                if (NOT_REQUIRED.matcher(sentence).find()) continue;
                if (NEGATION.matcher(sentence).find()) no = true;
                else yes = true;
            }
            if (AUTH.matcher(sentence).find()) req = true;
        }
        return no ? DOES_NOT_SPONSOR : yes ? SPONSORS : req ? AUTHORIZATION_REQUIRED : null;
    }

    /** "U.S." and "e.g." are not sentence ends. */
    private static boolean isInitialism(String t, int dot) {
        return dot >= 2 && Character.isLetter(t.charAt(dot - 1)) && t.charAt(dot - 2) == '.';
    }

    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        return s.substring(0, cut > 0 ? cut : max).strip();
    }
}
