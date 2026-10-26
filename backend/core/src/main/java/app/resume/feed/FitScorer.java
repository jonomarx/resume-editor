package app.resume.feed;

import java.util.List;
import java.util.Set;

/**
 * Interim tag-overlap fit (US-050): the share of the posting's tags the master resume also has, as an integer 0 to 100.
 * US-051 replaces this with the {@code Matcher}; until then it keeps {@code fitScore} and {@code fit} meaningful.
 */
final class FitScorer {
    record Fit(int score, List<String> matchedTags, List<String> missingTags) {}

    private FitScorer() {}

    static Fit score(List<String> postingTags, Set<String> masterTags) {
        List<String> matched = postingTags.stream().filter(masterTags::contains).toList();
        List<String> missing = postingTags.stream().filter(t -> !masterTags.contains(t)).toList();
        int score = postingTags.isEmpty() ? 0 : Math.round(100f * matched.size() / postingTags.size());
        return new Fit(score, matched, missing);
    }
}
