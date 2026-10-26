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
            + "what (you'?ll|you will|we'?re looking for|you need|you bring|we need)\\b.*(bring|need|have|for)|"
            + "who you are|about you|you have|you'?ll need|skills( and experience)?|what we'?re looking for|your background)\\b.*");
    private static final Pattern OTHER = Pattern.compile("(?i)^(responsibilit\\w*|what you'?ll do|what you will do|about (us|the (role|team|company|job))|"
            + "the role|overview|description|benefits|perks|compensation|salary|pay|how to apply|equal opportunity|our (mission|values|team)|"
            + "why (join|work)|location|life at|the team|company|applying|diversity|privacy|summary|about the internship|internship (details|overview))\\b.*");
    private static final Pattern INLINE = Pattern.compile("^([^:]{3," + MAX_HEADING + "}):\\s*(\\S.*)$");

    private RequirementSections() {}

    public static Sections parse(String text) {
        if (text == null) return new Sections(null, null);
        StringBuilder basic = new StringBuilder();
        StringBuilder preferred = new StringBuilder();
        Kind current = Kind.OTHER;
        for (String raw : text.split("\n", -1)) {
            String line = raw.strip();
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
            append(current, basic, preferred, line);
        }
        return new Sections(cap(basic), cap(preferred));
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
        return line.replaceAll("^[#*\\-•\\s]+|[*\\s]+$", "");
    }

    // Without a colon only these whole lines count, so "Basic knowledge of Java" stays a requirement, not a heading.
    private static final String MOD = "(?:preferred|desired|additional|bonus|great|nice[- ]to[- ]have)";
    private static final Pattern STRICT_PREFERRED = Pattern.compile("(?i)^(?:" + MOD + "\\s+(?:requirements?|qualifications?|skills?|experience)|"
            + "nice[- ]to[- ]haves?|bonus(?: points)?|preferred|desired|(?:it'?s )?a plus|extra credit|good to have|great to have)$");
    private static final Pattern STRICT_BASIC = Pattern.compile("(?i)^(?:requirements?|qualifications?|skills?|skills (?:and|&) experience|"
            + "what (?:you'?ll|you will) (?:bring|need)|what you (?:bring|need|have)|what we'?re looking for|who you are|about you|"
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
