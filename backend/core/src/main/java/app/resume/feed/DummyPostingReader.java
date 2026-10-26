package app.resume.feed;

import app.resume.jobs.PostingQuery;
import app.resume.jobs.PostingReader;
import app.resume.jobs.PostingView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DUMMY(US-030): in-memory stand-in for the Job List Manager's {@link PostingReader}, active only with the {@code dummy}
 * profile. Every user starts with a few sample postings; tests replace them with {@link #replace}. Delete this class when
 * the real reader (the role:jobs card "PostingReader implementation") reaches integration.
 */
@Component
@Profile("dummy")
public class DummyPostingReader implements PostingReader {
    private final Map<Long, List<PostingView>> byUser = new ConcurrentHashMap<>();

    /** Replaces one user's postings. */
    public void replace(long userId, List<PostingView> postings) {
        byUser.put(userId, List.copyOf(postings));
    }

    public void clear() {
        byUser.clear();
    }

    @Override
    public List<PostingView> search(long ownerUserId, PostingQuery q) {
        List<PostingView> out = new ArrayList<>();
        for (PostingView p : postingsOf(ownerUserId)) {
            if (p.closed() && !q.includeClosedIds().contains(p.id())) continue;
            if (!p.tags().containsAll(q.tagSlugs())) continue;
            if (q.location() != null && (p.location() == null
                    || !p.location().toLowerCase(Locale.ROOT).contains(q.location().toLowerCase(Locale.ROOT)))) continue;
            if (q.firstSeenSince() != null && p.firstSeenAt().isBefore(q.firstSeenSince())) continue;
            if (q.text() != null && !matchesText(p, q.text())) continue;
            out.add(p);
        }
        return out;
    }

    @Override
    public Optional<PostingView> find(long ownerUserId, long postingId) {
        return postingsOf(ownerUserId).stream().filter(p -> p.id() == postingId).findFirst();
    }

    private List<PostingView> postingsOf(long userId) {
        return byUser.getOrDefault(userId, SAMPLES);
    }

    private static boolean matchesText(PostingView p, String text) {
        String hay = (p.title() + " " + p.company() + " " + p.description()).toLowerCase(Locale.ROOT);
        for (String term : text.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(term)) return false;
        }
        return true;
    }

    private static final List<PostingView> SAMPLES = List.of(
            new PostingView(1, "Software Engineering Intern", "Acme", "Seattle, WA", "https://acme.example/careers/swe-intern",
                    "Build internal tools for the batch platform team.", List.of("Pursuing a BS in computer science"),
                    List.of("Familiarity with Postgres"), List.of("java", "postgresql"), "Summer 2027", null,
                    "Rising junior or senior", "active", "crawl", 3L, Instant.parse("2026-10-15T00:00:00Z"),
                    Instant.parse("2026-10-19T06:00:03Z"), Instant.parse("2026-10-20T06:00:05Z")),
            new PostingView(2, "Frontend Intern", "Globex", "Remote", "https://globex.example/jobs/fe-intern",
                    "Work on the React web app.", List.of("Some JavaScript"), List.of(), List.of("react", "typescript"),
                    "Summer 2027", null, null, "active", "ingest", null, null, Instant.parse("2026-10-18T06:00:00Z"),
                    Instant.parse("2026-10-18T06:00:00Z")),
            new PostingView(3, "Data Intern", "Initech", "Austin, TX", "https://initech.example/data-intern",
                    "Analyze data.", List.of(), List.of(), List.of("python"), null, null, null, "active", "crawl", 4L,
                    null, Instant.parse("2026-10-10T06:00:00Z"), Instant.parse("2026-10-20T06:00:00Z")));
}
