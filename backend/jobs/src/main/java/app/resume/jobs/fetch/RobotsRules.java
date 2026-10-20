package app.resume.jobs.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The part of one robots.txt that applies to our user agent. Longest matching rule wins; on a tie Allow wins; the
 * {@code *} and {@code $} wildcards are supported. A group naming our agent token beats the {@code *} group.
 */
public final class RobotsRules {
    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

    private record Rule(boolean allow, String pattern) {}

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    public static RobotsRules parse(String robotsTxt, String agentToken) {
        String token = agentToken.toLowerCase(Locale.ROOT);
        List<Rule> specific = new ArrayList<>();
        List<Rule> wildcard = new ArrayList<>();
        boolean sawSpecific = false;
        List<String> groupAgents = new ArrayList<>();
        boolean inRules = false;
        for (String raw : robotsTxt.split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String field = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (field.equals("user-agent")) {
                if (inRules) {
                    groupAgents = new ArrayList<>();
                    inRules = false;
                }
                groupAgents.add(value.toLowerCase(Locale.ROOT));
                if (groupAgents.contains(token)) sawSpecific = true;
            } else if (field.equals("allow") || field.equals("disallow")) {
                inRules = true;
                if (value.isEmpty()) continue; // "Disallow:" with no path allows everything
                Rule rule = new Rule(field.equals("allow"), value);
                if (groupAgents.contains(token)) specific.add(rule);
                else if (groupAgents.contains("*")) wildcard.add(rule);
            }
        }
        return new RobotsRules(sawSpecific ? specific : wildcard);
    }

    public boolean allows(String pathAndQuery) {
        Rule best = null;
        int bestLen = -1;
        for (Rule r : rules) {
            if (!matches(r.pattern, pathAndQuery)) continue;
            int len = r.pattern.length();
            if (len > bestLen || (len == bestLen && r.allow)) {
                best = r;
                bestLen = len;
            }
        }
        return best == null || best.allow;
    }

    private static boolean matches(String pattern, String path) {
        boolean anchored = pattern.endsWith("$");
        String p = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        return globMatch(p, 0, path, 0, anchored);
    }

    private static boolean globMatch(String p, int pi, String s, int si, boolean anchored) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                for (int k = si; k <= s.length(); k++) if (globMatch(p, pi + 1, s, k, anchored)) return true;
                return false;
            }
            if (si >= s.length() || s.charAt(si) != c) return false;
            pi++;
            si++;
        }
        return !anchored || si == s.length();
    }
}
