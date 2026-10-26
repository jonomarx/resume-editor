package app.resume.feed;

import app.resume.jobs.PostingView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code MATCHER=tags}: the share of a posting's tag weight that the master resume covers. A tag's weight is
 * {@code ln(1 + N / df)}, where N is the number of postings in the corpus and df how many carry the tag, so a rare tag
 * counts more than one almost every posting has. Score = round(100 * covered weight / total weight of the posting's tags).
 */
public class TagOverlapMatcher implements Matcher {
    @Override
    public Map<Long, Match> match(Set<String> masterTags, List<PostingView> candidates, List<PostingView> corpus, HardFilters hard) {
        Map<String, Integer> df = new HashMap<>();
        for (PostingView p : corpus) for (String t : Set.copyOf(p.tags())) df.merge(t, 1, Integer::sum);
        int n = Math.max(corpus.size(), 1);
        Map<Long, Match> out = new LinkedHashMap<>();
        for (PostingView p : candidates) {
            if (!passes(p, hard)) continue;
            List<String> matched = p.tags().stream().filter(masterTags::contains).toList();
            List<String> missing = p.tags().stream().filter(t -> !masterTags.contains(t)).toList();
            double total = 0, covered = 0;
            for (String t : p.tags()) {
                double w = Math.log(1.0 + (double) n / Math.max(df.getOrDefault(t, 1), 1));
                total += w;
                if (masterTags.contains(t)) covered += w;
            }
            int score = total == 0 ? 0 : (int) Math.round(100.0 * covered / total);
            out.put(p.id(), new Match(score, matched, missing));
        }
        return out;
    }

    static boolean passes(PostingView p, HardFilters h) {
        return same(h.season(), p.season()) && same(h.workAuthorization(), p.workAuthorization())
                && same(h.classYear(), p.classYear()) && contains(h.location(), p.location());
    }

    private static boolean same(String wanted, String actual) {
        return wanted == null || actual == null || wanted.trim().equalsIgnoreCase(actual.trim());
    }

    private static boolean contains(String wanted, String actual) {
        return wanted == null || actual == null || actual.toLowerCase(Locale.ROOT).contains(wanted.trim().toLowerCase(Locale.ROOT));
    }
}
