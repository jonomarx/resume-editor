package app.resume.jobs.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits posting text into sections by heading lines ("Requirements", "Nice to have:", ...) and returns the text under the
 * requirement and preferred headings, verbatim. Nothing is paraphrased or invented: no heading, no requirement text.
 */
public final class RequirementSections {
    public record Sections(String basic, String preferred) {}

    enum Kind { BASIC, PREFERRED, OTHER }

    static final int MAX_FIELD = 2000;
    private static final int MAX_HEADING = 60;

    private static final Pattern PREFERRED = Pattern.compile("(?i)^(preferred|nice[- ]to[- ]haves?|bonus|desired|ideal|"
            + "(it'?s |it is )?a plus|extra credit|good to have|additional qualifications|great to have)\\b.*");
    private static final Pattern BASIC = Pattern.compile("(?i)^(requirements?|qualifications?|basic|minimum|required|must[- ]haves?|"
            + "what (you'?ll|you will|we'?re|we are|you|we)\\b.*(bring|need|have|for|seek)|"
            + "who you are|about you|you have|you'?ll need|skills( and experience)?|what we'?re looking for|your background)\\b.*");
    private static final Pattern OTHER = Pattern.compile("(?i)^(responsibilit\\w*|what you'?ll do|what you will do|about (us|the (role|team|company|job))|"
            + "the role|overview|description|benefits|perks|compensation|salary|pay|how to apply|equal opportunity|our (mission|values|team)|"
            + "why (join|work)|location|life at|the team|company|applying|diversity|privacy|summary|about the internship|internship (details|overview))\\b.*");
    private static final Pattern INLINE = Pattern.compile("^([^:]{3," + MAX_HEADING + "}):\\s*(\\S.*)$");

    private RequirementSections() {}

    public static Sections parse(String text) {
        if (text == null) return new Sections(null, null);
        text = PostingText.normalize(text);
        StringBuilder basic = new StringBuilder();
        StringBuilder preferred = new StringBuilder();
        Kind current = Kind.OTHER;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            Kind heading = headingKind(stripMarks(line));
            if (heading != null) {
                current = heading;
                Matcher m = INLINE.matcher(line);
                if (m.matches() && headingKind(stripMarks(m.group(1))) != null) { // "Requirements: BS in CS" keeps the rest
                    current = headingKind(stripMarks(m.group(1)));
                    append(current, basic, preferred, m.group(2));
                }
                continue;
            }
            if (current != Kind.OTHER) {
                // A prose paragraph, or a short unknown heading before one, ends the section: requirement items are short lines.
                if (line.length() > LONG_LINE || i > 0 && lines[i - 1].isBlank() && looksLikeHeading(line, lines, i)) {
                    current = Kind.OTHER;
                    continue;
                }
            }
            append(current, basic, preferred, line);
        }
        return new Sections(cap(basic), cap(preferred));
    }

    private static final int LONG_LINE = 200;

    private static boolean looksLikeHeading(String line, String[] lines, int i) {
        if (line.length() > 40 || line.endsWith(".") || line.endsWith(",") || line.startsWith("-") || line.startsWith("•") || line.startsWith("*")) return false;
        for (int k = i + 1; k < lines.length; k++) {
            if (!lines[k].isBlank()) return lines[k].strip().length() > 100;
        }
        return false;
    }

    private static void append(Kind k, StringBuilder basic, StringBuilder preferred, String line) {
        StringBuilder target = k == Kind.BASIC ? basic : k == Kind.PREFERRED ? preferred : null;
        if (target == null || target.length() > MAX_FIELD) return;
        if (target.length() > 0) target.append('\n');
        target.append(line);
    }

    /** Only the requirement text lines (basic then preferred), for scoping tag matching. */
    public static String scoped(Sections s) {
        List<String> parts = new ArrayList<>();
        if (s.basic() != null) parts.add(s.basic());
        if (s.preferred() != null) parts.add(s.preferred());
        return String.join("\n", parts);
    }

    private static String cap(StringBuilder b) {
        if (b.length() == 0) return null;
        String s = b.length() > MAX_FIELD ? b.substring(0, MAX_FIELD) : b.toString();
        return s.strip();
    }

    private static String stripMarks(String line) {
        int a = 0, b = line.length();
        while (a < b && "#*-•".indexOf(line.charAt(a)) >= 0 || a < b && line.charAt(a) == ' ') a++;
        while (b > a && (line.charAt(b - 1) == '*' || line.charAt(b - 1) == ' ')) b--;
        return line.substring(a, b);
    }

    private static final Pattern SKILL_LIST = Pattern.compile("(?i)^(?:programming languages|languages|tech(?:nolog(?:y|ies))?(?: stack)?|stack|tools|skills|"
            + "tech stack)\\s{0,3}:\\s{0,3}(\\S.*)$");

    /** Lines that are an explicit skills or tech-stack list ("Tech stack: Java, Spring"): their text, without the label. */
    public static String skillLists(String text) {
        StringBuilder out = new StringBuilder();
        for (String raw : PostingText.normalize(text).split("\n", -1)) {
            Matcher m = SKILL_LIST.matcher(stripMarks(raw.strip()));
            if (m.matches()) out.append(m.group(1)).append('\n');
        }
        return out.toString();
    }

    // Without a colon only these whole lines count, so "Basic knowledge of Java" stays a requirement, not a heading.
    private static final String MOD = "(?:preferred|desired|additional|bonus|great|nice[- ]to[- ]have)";
    private static final Pattern STRICT_PREFERRED = Pattern.compile("(?i)^(?:" + MOD + "\\s+(?:requirements?|qualifications?|skills?|experience)|"
            + "nice[- ]to[- ]haves?|bonus(?: points)?|preferred|desired|(?:it'?s )?a plus|extra credit|good to have|great to have)$");
    private static final Pattern STRICT_BASIC = Pattern.compile("(?i)^(?:requirements?|qualifications?|skills?|skills (?:and|&) experience|"
            + "what (?:you'?ll|you will) (?:bring|need)|what you (?:bring|need|have)|what we(?:'re| are) looking for|who you are|about you|"
            + "you have|you'?ll need|must[- ]haves?|your background|minimum (?:requirements?|qualifications?)|"
            + "basic (?:requirements?|qualifications?)|required (?:skills|qualifications?|experience))$");
    private static final Pattern STRICT_OTHER = Pattern.compile("(?i)^(?:responsibilit\\w*|what you'?ll do|what you will do|about (?:us|the (?:role|team|company|job)|you)|"
            + "the role|overview|(?:job )?description|benefits|perks(?: and benefits)?|compensation|salary|how to apply|"
            + "equal opportunity(?: employer)?|our (?:mission|values|team)|why (?:join|work)\\b.*|location|the team|about the internship|diversity|summary)$");

    /** A heading is a short line that starts with a known heading phrase and ends in a colon, or is exactly a known heading. */
    private static Kind headingKind(String line) {
        Matcher m = INLINE.matcher(line);
        boolean colon = m.matches() || line.endsWith(":");
        String head = m.matches() ? m.group(1) : line;
        head = head.replaceAll(":$", "").strip();
        if (head.length() > MAX_HEADING || head.endsWith(".")) return null;
        if (colon) {
            if (PREFERRED.matcher(head).matches()) return Kind.PREFERRED;
            if (BASIC.matcher(head).matches()) return Kind.BASIC;
            if (OTHER.matcher(head).matches()) return Kind.OTHER;
            return null;
        }
        if (STRICT_PREFERRED.matcher(head).matches()) return Kind.PREFERRED;
        if (STRICT_BASIC.matcher(head).matches()) return Kind.BASIC;
        if (STRICT_OTHER.matcher(head).matches()) return Kind.OTHER;
        return null;
    }
}
