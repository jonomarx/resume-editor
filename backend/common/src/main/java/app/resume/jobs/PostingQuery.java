package app.resume.jobs;

import java.time.Instant;
import java.util.Set;

/**
 * Filters of {@link PostingReader#search}. Null or empty means "no filter" for that criterion.
 *
 * @param tagSlugs canonical tag slugs; a posting must carry every one
 * @param location case-insensitive substring of the posting's location
 * @param firstSeenSince only postings first seen at or after this instant
 * @param text search over title, company and description: case-insensitive, and every whitespace-separated term must occur
 *        in at least one of the three
 * @param includeClosedIds ids of closed postings that stay visible (for example those a draft references)
 */
public record PostingQuery(Set<String> tagSlugs, String location, Instant firstSeenSince, String text,
        Set<Long> includeClosedIds) {
    public PostingQuery {
        tagSlugs = tagSlugs == null ? Set.of() : Set.copyOf(tagSlugs);
        includeClosedIds = includeClosedIds == null ? Set.of() : Set.copyOf(includeClosedIds);
    }

    /** No filters: every posting that is not closed. */
    public static PostingQuery all() {
        return new PostingQuery(null, null, null, null, null);
    }
}
