package app.resume.feed;

import app.resume.jobs.PostingView;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores postings against the master resume, per request (DESIGN section 6). Application code depends on this interface
 * only; the implementation is chosen by {@code MATCHER} (application.yml {@code app.matcher.type}): {@code tags} is the
 * weighted tag-overlap matcher. Later stages (embeddings, rerank) are new implementations.
 *
 * <p>Full-text search is not part of scoring: it is the {@code q} filter of the posting reader port, where the posting
 * tables live.
 */
public interface Matcher {
    /**
     * The user's hard requirements. A null field means "no requirement". A posting that states a different value for a
     * set field is excluded; a posting that does not say (null) is kept. Comparison is case-insensitive; a location
     * matches as a substring.
     */
    record HardFilters(String season, String workAuthorization, String classYear, String location) {
        public static final HardFilters NONE = new HardFilters(null, null, null, null);
    }

    /** @param score integer 0 to 100; 0 when the master has no tags or the posting has none */
    record Match(int score, List<String> matchedTags, List<String> missingTags) {}

    /**
     * Scores the candidates. Postings excluded by the hard filters are absent from the result.
     *
     * @param masterTags slugs on the master's text nodes
     * @param corpus all the user's postings, used to weight tags by rarity (so a score does not change with the filters
     *        of the request)
     */
    Map<Long, Match> match(Set<String> masterTags, List<PostingView> candidates, List<PostingView> corpus, HardFilters hard);
}
