package app.resume.jobs.enrich;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pattern rules for season, class year and work authorization. Each returns the matched phrase verbatim or null; never a guess. */
public final class EligibilityRules {
    private EligibilityRules() {}

    private static final Pattern SEASON = Pattern.compile("(?i)\\b(summer|fall|autumn|winter|spring)\\s+(?:of\\s+)?(20\\d\\d)\\b");
    private static final Pattern CLASS_YEAR = Pattern.compile("(?i)\\b(?:"
            + "rising\\s+(?:freshmen|freshman|sophomores?|juniors?|seniors?)(?:\\s*(?:,|or|and|/)\\s*(?:rising\\s+)?(?:sophomores?|juniors?|seniors?))*"
            + "|class\\s+of\\s+20\\d\\d"
            + "|graduat(?:ing|ion)(?:\\s+date)?(?:\\s+(?:in|on|by|between|of)|\\s*:)?\\s*(?:(?:spring|summer|fall|winter|december|may|june)\\s+)?(?:of\\s+)?20\\d\\d(?:\\s*(?:-|to|or|and)\\s*20\\d\\d)?"
            + "|expected\\s+graduation(?:\\s+date)?(?:\\s+(?:in|of)|\\s*:)?\\s*(?:(?:spring|summer|fall|winter|december|may|june)\\s+)?20\\d\\d"
            + "|(?:juniors?|seniors?|sophomores?)\\s*(?:,|or|and|/)\\s*(?:juniors?|seniors?)"
            + ")\\b");
    private static final Pattern AUTH = Pattern.compile("(?i)(authori[sz]ed to work|work authori[sz]ation|eligible to work|legally (?:authori[sz]ed|entitled) to work|"
            + "right to work|(?:visa )?sponsorship|u\\.?s\\.? citizen|citizenship|permanent resident|green card|security clearance)");
    private static final int MAX_AUTH = 120;

    public static String season(String text) {
        if (text == null) return null;
        Matcher m = SEASON.matcher(text);
        if (!m.find()) return null;
        String s = m.group(1);
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase() + " " + m.group(2);
    }

    public static String classYear(String text) {
        if (text == null) return null;
        Matcher m = CLASS_YEAR.matcher(text);
        return m.find() ? clip(m.group().replaceAll("\\s+", " ").strip(), 40) : null;
    }

    /** The sentence (or line) that mentions work authorization, whitespace-collapsed and cut at a word boundary. */
    public static String workAuthorization(String text) {
        if (text == null) return null;
        Matcher m = AUTH.matcher(text);
        if (!m.find()) return null;
        int from = lastBreak(text, m.start()) + 1;
        int to = nextBreak(text, m.end());
        String sentence = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return clip(sentence, MAX_AUTH);
    }

    private static int lastBreak(String t, int i) {
        for (int k = i - 1; k >= 0; k--) {
            char c = t.charAt(k);
            if (c == '\n' || c == '.' && (k + 1 < t.length() && Character.isWhitespace(t.charAt(k + 1)))) return k;
        }
        return -1;
    }

    private static int nextBreak(String t, int i) {
        for (int k = i; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\n' || c == '.' && (k + 1 >= t.length() || Character.isWhitespace(t.charAt(k + 1)))) return k + 1;
        }
        return t.length();
    }

    private static String clip(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max);
        return s.substring(0, cut > 0 ? cut : max).strip();
    }
}
