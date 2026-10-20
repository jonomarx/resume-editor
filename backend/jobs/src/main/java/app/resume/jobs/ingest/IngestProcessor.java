package app.resume.jobs.ingest;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.enrich.PostingEnricher;
import app.resume.jobs.extract.ExtractionOutputException;
import app.resume.jobs.extract.LlmPostingExtractor;
import app.resume.jobs.fetch.ExtractedPosting;
import app.resume.jobs.fetch.FetchException;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.fetch.RobotsDisallowedException;
import app.resume.jobs.fetch.UrlNotAllowedException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Processes one ingest event: reads the posting out of the URL's page, HTML, text or email (JSON-LD first, then the LLM),
 * and stores it as a one-time posting (origin ingest, no source, so it is never re-crawled but is validity-checked daily).
 * Input is untrusted data. A claim (queued to processing) is a guarded UPDATE, so two workers never process one event.
 */
public class IngestProcessor {
    public static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(IngestProcessor.class);

    private record Event(long id, long userId, String kind, String payload, String sourceHint) {}

    private final JdbcTemplate jdbc;
    private final PageFetcher fetcher;
    private final JsonLdPostingAdapter jsonLd;
    private final LlmPostingExtractor llm; // may be null
    private final PostingEnricher enricher; // may be null
    private final Supplier<Instant> clock;

    public IngestProcessor(JdbcTemplate jdbc, PageFetcher fetcher, JsonLdPostingAdapter jsonLd, LlmPostingExtractor llm, PostingEnricher enricher,
            Supplier<Instant> clock) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.jsonLd = jsonLd;
        this.llm = llm;
        this.enricher = enricher;
        this.clock = clock;
    }

    /** @return true if this call processed the event, false if someone else holds it or it is already final */
    public boolean process(long eventId) {
        if (jdbc.update("UPDATE ingest_event SET status = 'processing', processing_started_at = ? WHERE id = ? AND status = 'queued'",
                Timestamp.from(clock.get()), eventId) != 1) {
            return false;
        }
        Event e = jdbc.queryForObject("SELECT id, user_id, kind, payload, source_hint FROM ingest_event WHERE id = ?",
                (rs, i) -> new Event(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), eventId);
        try {
            long postingId = ingest(e);
            finish(eventId, "done", postingId, null, null);
            // The payload is no longer needed once processed; do not keep pasted pages around.
            jdbc.update("UPDATE ingest_event SET payload = NULL WHERE id = ?", eventId);
        } catch (Failure f) {
            finish(eventId, "failed", null, f.code, f.getMessage());
        } catch (UrlNotAllowedException ex) {
            finish(eventId, "failed", null, UrlNotAllowedException.CODE, "That address is not a public website");
        } catch (RobotsDisallowedException ex) {
            finish(eventId, "failed", null, "robots_disallowed", "The site does not allow fetching that page");
        } catch (FetchException ex) {
            finish(eventId, "failed", null, "fetch_failed", "The page could not be fetched");
        } catch (ExtractionOutputException ex) {
            finish(eventId, "failed", null, "extraction_failed", "The posting could not be read");
        } catch (RuntimeException ex) {
            log.error("ingest event {} crashed", eventId, ex);
            finish(eventId, "failed", null, "internal_error", "Unexpected error");
        }
        return true;
    }

    /** Recovery sweep: take back claims that never finished, then process everything still queued. */
    public int recoverAndProcessPending() {
        jdbc.update("UPDATE ingest_event SET status = 'queued' WHERE status = 'processing' AND processing_started_at < ?",
                Timestamp.from(clock.get().minus(CLAIM_TIMEOUT)));
        int n = 0;
        for (long id : jdbc.queryForList("SELECT id FROM ingest_event WHERE status = 'queued' ORDER BY id", Long.class)) {
            if (process(id)) n++;
        }
        return n;
    }

    private static final class Failure extends RuntimeException {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private long ingest(Event e) {
        String pageUrl = null;
        List<ExtractedPosting> found;
        switch (e.kind) {
            case "url" -> {
                pageUrl = e.payload;
                FetchResult r = fetcher.fetch(pageUrl);
                if (!r.ok()) throw new FetchException("HTTP " + r.status());
                found = extractFromHtml(r.body(), r.finalUrl());
            }
            case "html" -> found = extractFromHtml(e.payload, hintUrl(e.sourceHint).orElse("https://ingested.invalid/"));
            default -> found = extractWithLlm(e.payload, e.sourceHint); // text and email
        }
        if (found.isEmpty()) throw new Failure("no_posting_found", "No job posting was found in that input");
        ExtractedPosting p = found.get(0);
        if (pageUrl != null && found.size() > 1) {
            String wanted = PostingNormalizer.canonicalUrl(pageUrl);
            p = found.stream().filter(x -> x.url() != null && sameUrl(x.url(), wanted)).findFirst()
                    .orElseThrow(() -> new Failure("no_posting_found", "The page lists several postings; send the URL of one posting"));
        }
        return store(e, p, pageUrl);
    }

    private List<ExtractedPosting> extractFromHtml(String html, String baseUrl) {
        List<ExtractedPosting> found = jsonLd.extract(html, baseUrl);
        return found.isEmpty() ? extractWithLlm(html, baseUrl) : found;
    }

    private List<ExtractedPosting> extractWithLlm(String content, String hint) {
        if (llm == null) throw new Failure("extraction_failed", "No posting reader is configured");
        return llm.extract(content, hint == null ? "https://ingested.invalid/" : hint);
    }

    private static boolean sameUrl(String a, String canonicalB) {
        try {
            return PostingNormalizer.canonicalUrl(a).equals(canonicalB);
        } catch (IllegalArgumentException x) {
            return false;
        }
    }

    private static Optional<String> hintUrl(String hint) {
        if (hint == null) return Optional.empty();
        try {
            return Optional.of(PostingNormalizer.canonicalUrl(hint));
        } catch (IllegalArgumentException x) {
            return Optional.empty();
        }
    }

    private long store(Event e, ExtractedPosting p, String pageUrl) {
        String company = p.company() == null ? "Unknown" : p.company();
        String contentHash = PostingNormalizer.contentHash(p.title(), company, p.location(), p.description());
        String dedupe = PostingNormalizer.dedupeKey(company, p.title(), p.location());
        String url;
        if (pageUrl != null) {
            url = PostingNormalizer.canonicalUrl(pageUrl);
        } else {
            Optional<String> own = Optional.ofNullable(p.url()).flatMap(IngestProcessor::hintUrl);
            url = own.or(() -> hintUrl(e.sourceHint)).orElse("https://ingested.invalid/p/" + contentHash.substring(0, 24));
        }
        String urlHash = PostingNormalizer.urlHash(url);
        List<Long> byUrl = jdbc.queryForList("SELECT id FROM job_posting WHERE owner_user_id = ? AND url_hash = ?", Long.class, e.userId, urlHash);
        if (!byUrl.isEmpty()) return byUrl.get(0);
        List<Long> byRole = jdbc.queryForList("SELECT id FROM job_posting WHERE owner_user_id = ? AND dedupe_key = ? ORDER BY id", Long.class, e.userId, dedupe);
        if (!byRole.isEmpty()) return byRole.get(0);
        Timestamp now = Timestamp.from(clock.get());
        boolean created = true;
        try {
            jdbc.update("INSERT INTO job_posting (owner_user_id, source_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company,"
                    + " location, description, first_seen_at, last_seen_at, created_at, updated_at) VALUES (?, NULL, 'ingest', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    e.userId, url, urlHash, contentHash, dedupe, p.title(), company, p.location(), p.description(), now, now, now, now);
        } catch (DuplicateKeyException race) {
            created = false; // a crawl (or another ingest) stored the same URL first; the unique constraint kept it single
        }
        long id = jdbc.queryForObject("SELECT id FROM job_posting WHERE owner_user_id = ? AND url_hash = ?", Long.class, e.userId, urlHash);
        if (created && enricher != null) {
            try {
                enricher.enrich(id);
            } catch (RuntimeException ex) {
                log.warn("enrichment of posting {} failed: {}", id, ex.toString());
            }
        }
        return id;
    }

    private void finish(long eventId, String status, Long postingId, String code, String message) {
        jdbc.update("UPDATE ingest_event SET status = ?, processed_at = ?, posting_id = ?, error_code = ?, error_message = ? WHERE id = ?",
                status, Timestamp.from(clock.get()), postingId, code, message, eventId);
    }
}
