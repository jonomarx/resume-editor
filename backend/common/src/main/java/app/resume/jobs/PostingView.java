package app.resume.jobs;

import java.time.Instant;
import java.util.List;

/**
 * A posting as core sees it (read-only snapshot). Lists are never null. {@code description} is the full text; core shortens
 * it for the feed.
 *
 * @param location null when the posting has none
 * @param tags canonical slugs from the shared vocabulary, never free text
 * @param season eligibility fields: null when the posting does not say
 * @param status {@code active}, {@code stale} or {@code closed}
 * @param origin {@code crawl} or {@code ingest}
 * @param sourceId null for one-time (ingested) postings
 * @param postedAt when the employer posted it; null when unknown (the job tables do not store it yet)
 */
public record PostingView(long id, String title, String company, String location, String url, String description,
        List<String> basicRequirements, List<String> preferredRequirements, List<String> tags, String season,
        String workAuthorization, String classYear, String status, String origin, Long sourceId, Instant postedAt,
        Instant firstSeenAt, Instant lastSeenAt) {
    public PostingView {
        basicRequirements = basicRequirements == null ? List.of() : List.copyOf(basicRequirements);
        preferredRequirements = preferredRequirements == null ? List.of() : List.copyOf(preferredRequirements);
        tags = tags == null ? List.of() : List.copyOf(tags);
        description = description == null ? "" : description;
    }

    public boolean closed() {
        return "closed".equals(status);
    }
}
