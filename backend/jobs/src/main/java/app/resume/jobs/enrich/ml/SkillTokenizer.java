package app.resume.jobs.enrich.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits untrusted posting text into bounded sentences of tokens that keep names such as C++, C#, .NET and Node.js whole. */
public final class SkillTokenizer {
    public static final int MAX_CHARS = 20_000;
    public static final int MAX_TOKENS = 6_000;
    public static final int MAX_SENTENCE_TOKENS = 120;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]+(?:[.+#/-][A-Za-z0-9_]+|[+#]+)*|\\.[A-Za-z][A-Za-z0-9]*|[^\\sA-Za-z0-9_]");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;])\\s+(?=[A-Z])");

    private SkillTokenizer() {}

    /** Sentences (lines, then sentence ends) as token arrays; at most {@link #MAX_TOKENS} tokens in total. */
    public static List<String[]> sentences(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) return out;
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        int budget = MAX_TOKENS;
        for (String line : text.split("\\r?\\n")) {
            for (String sentence : SENTENCE_END.split(line)) {
                List<String> tokens = tokens(sentence, Math.min(budget, MAX_SENTENCE_TOKENS));
                if (tokens.isEmpty()) continue;
                budget -= tokens.size();
                out.add(tokens.toArray(new String[0]));
                if (budget <= 0) return out;
            }
        }
        return out;
    }

    static List<String> tokens(String sentence, int max) {
        List<String> t = new ArrayList<>();
        Matcher m = TOKEN.matcher(sentence);
        while (m.find() && t.size() < max) {
            String g = m.group();
            t.add(g.length() > 40 ? g.substring(0, 40) : g);
        }
        return t;
    }
}
