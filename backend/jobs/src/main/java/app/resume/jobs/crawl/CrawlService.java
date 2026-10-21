package app.resume.jobs.crawl;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.enrich.PostingEnricher;
import app.resume.jobs.extract.ExtractionOutputException;
import app.resume.jobs.extract.LlmPostingExtractor;
import app.resume.jobs.fetch.AtsFeedAdapter;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crawls one source (steps 2 to 8 of the daily run). The per-source lock makes a second concurrent run of the same
 * source a no-op; unique (owner_user_id, url_hash) is the final guard against duplicate postings.
 *
 * <p>Lifecycle of a posting of this source: seen in the listing, it is active (a changed one is updated, a stale or
 * closed one comes back); missing from {@code staleAfter} runs in a row it becomes stale, from {@code closeAfter} runs
 * it becomes closed. Closed postings are hidden by the feed and removed by the retention job, not here.
 */
public class CrawlService {
    public enum Outcome { SUCCEEDED, SKIPPED_UNCHANGED, FAILED, LOCKED }

    public record Result(Outcome outcome, Long crawlRunId) {}

    public static final int DEFAULT_STALE_AFTER = 2;
    public static final int DEFAULT_CLOSE_AFTER = 4;
    public static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    private record Source(long id, long userId, String name, String url, String contentHash) {}

    private record Extraction(String tier, List<ExtractedPosting> postings, String contentHash) {}

    private final JdbcTemplate jdbc;
    private final PageFetcher fetcher;
    private final List<AtsFeedAdapter> atsAdapters;
    private final JsonLdPostingAdapter jsonLd;
    private final LlmPostingExtractor llm; // null when no LLM is configured
    private final PostingEnricher enricher; // null when enrichment is not configured
    private final java.util.function.Supplier<Instant> clock;
    private final int staleAfter;
    private final int closeAfter;

    public CrawlService(JdbcTemplate jdbc, PageFetcher fetcher, List<AtsFeedAdapter> atsAdapters, JsonLdPostingAdapter jsonLd,
            LlmPostingExtractor llm, PostingEnricher enricher, java.util.function.Supplier<Instant> clock, int staleAfter,
            int closeAfter) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.atsAdapters = atsAdapters;
        this.jsonLd = jsonLd;
        this.llm = llm;
        this.enricher = enricher;
        this.clock = clock;
        this.staleAfter = staleAfter;
        this.closeAfter = closeAfter;
    }

    public Result crawl(long sourceId) {
        Instant now = clock.get();
        if (jdbc.update("UPDATE job_source SET crawl_lock_until = ? WHERE id = ? AND enabled = TRUE"
                + " AND (crawl_lock_until IS NULL OR crawl_lock_until < ?)", ts(now.plus(LOCK_TTL)), sourceId, ts(now)) != 1) {
            return new Result(Outcome.LOCKED, null);
        }
        try {
            return crawlLocked(sourceId, now);
        } finally {
            jdbc.update("UPDATE job_source SET crawl_lock_until = NULL WHERE id = ?", sourceId);
        }
    }

    private Result crawlLocked(long sourceId, Instant startedAt) {
        Source source = jdbc.queryForObject("SELECT id, user_id, name, url, content_hash FROM job_source WHERE id = ?",
                (rs, i) -> new Source(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)), sourceId);
        jdbc.update("INSERT INTO crawl_run (user_id, source_id, status, started_at) VALUES (?, ?, 'running', ?)",
                source.userId, sourceId, ts(startedAt));
        long runId = jdbc.queryForObject("SELECT MAX(id) FROM crawl_run WHERE source_id = ?", Long.class, sourceId);
        try {
            Extraction ex = extract(source);
            if (ex.contentHash != null && ex.contentHash.equals(source.contentHash)) {
                finish(runId, "skipped_unchanged", ex.tier, 0, 0, 0, 0, 0, null);
                touchSource(sourceId, ex.tier, null);
                return new Result(Outcome.SKIPPED_UNCHANGED, runId);
            }
            if (ex.postings.isEmpty() && activeCount(sourceId) > 0) {
                // An empty listing for a source that had postings is more likely a broken extraction than a mass closure.
                throw new CrawlFailure("empty_listing", "The page yielded no postings; existing postings were left alone");
            }
            int[] stats = apply(source, ex, runId, startedAt);
            finish(runId, "succeeded", ex.tier, stats[0], stats[1], stats[2], stats[3], stats[4], null);
            touchSource(sourceId, ex.tier, ex.contentHash);
            return new Result(Outcome.SUCCEEDED, runId);
        } catch (CrawlFailure f) {
            return failed(runId, sourceId, f.code, f.getMessage());
        } catch (UrlNotAllowedException e) {
            return failed(runId, sourceId, UrlNotAllowedException.CODE, e.getMessage());
        } catch (RobotsDisallowedException e) {
            return failed(runId, sourceId, "robots_disallowed", e.getMessage());
        } catch (FetchException e) {
            return failed(runId, sourceId, "fetch_failed", e.getMessage());
        } catch (ExtractionOutputException e) {
            return failed(runId, sourceId, "extraction_failed", e.getMessage());
        } catch (app.resume.llm.LlmInvalidOutputException e) {
            return failed(runId, sourceId, "extraction_failed", e.getMessage());
        } catch (app.resume.llm.LlmException e) {
            return failed(runId, sourceId, e.code(), e.getMessage()); // llm_unavailable, llm_budget_exceeded
        } catch (RuntimeException e) {
            log.error("crawl of source {} crashed", sourceId, e);
            return failed(runId, sourceId, "internal_error", "Unexpected error");
        }
    }

    private static final class CrawlFailure extends RuntimeException {
        final String code;

        CrawlFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private Result failed(long runId, long sourceId, String code, String message) {
        String shortMessage = message == null ? code : (message.length() > 500 ? message.substring(0, 500) : message);
        finish(runId, "failed", null, 0, 0, 0, 0, 0, code + ": " + shortMessage);
        return new Result(Outcome.FAILED, runId);
    }

    /** Tier 0 (ATS feed), then tier 1 (JSON-LD), then tier 3 (LLM) if one is configured. */
    private Extraction extract(Source s) {
        for (AtsFeedAdapter a : atsAdapters) {
            Optional<String> feed = a.feedUrl(s.url);
            if (feed.isPresent()) {
                FetchResult r = fetcher.fetch(feed.get());
                if (!r.ok()) throw new FetchException("ATS feed returned HTTP " + r.status());
                return new Extraction("ats_feed", a.parse(r.body(), s.url), PostingNormalizer.sha256(r.body()));
            }
        }
        FetchResult page = fetcher.fetch(s.url);
        if (!page.ok()) throw new FetchException("career page returned HTTP " + page.status());
        String hash = PostingNormalizer.sha256(page.body() == null ? "" : page.body());
        if (hash.equals(s.contentHash)) return new Extraction("unchanged", List.of(), hash);
        List<ExtractedPosting> found = jsonLd.extract(page.body(), page.finalUrl());
        if (!found.isEmpty()) return new Extraction("json_ld", found, hash);
        if (llm == null) throw new CrawlFailure("extraction_failed", "No postings found with JSON-LD and no LLM is configured");
        return new Extraction("llm", llm.extract(page.body(), page.finalUrl()), hash);
    }

    /** Adds, updates and culls. Returns {seen, added, updated, staledOrClosed, unusedZero}. */
    private int[] apply(Source s, Extraction ex, long runId, Instant now) {
        int seen = 0;
        int added = 0;
        int updated = 0;
        Set<Long> seenIds = new HashSet<>();
        List<Long> toEnrich = new ArrayList<>();
        for (ExtractedPosting p : ex.postings) {
            String company = p.company() == null ? s.name : p.company();
            String url;
            try {
                url = PostingNormalizer.canonicalUrl(p.url() != null ? p.url() : synthesizedUrl(s, p, company));
            } catch (IllegalArgumentException bad) {
                continue; // a posting whose URL we cannot use is skipped, not fatal
            }
            seen++;
            String urlHash = PostingNormalizer.urlHash(url);
            String contentHash = PostingNormalizer.contentHash(p.title(), company, p.location(), p.description());
            String dedupe = PostingNormalizer.dedupeKey(company, p.title(), p.location());
            List<long[]> existing = jdbc.query("SELECT id FROM job_posting WHERE owner_user_id = ? AND url_hash = ?",
                    (rs, i) -> new long[] {rs.getLong(1)}, s.userId, urlHash);
            long id;
            if (!existing.isEmpty()) {
                id = existing.get(0)[0];
                String oldHash = jdbc.queryForObject("SELECT content_hash FROM job_posting WHERE id = ?", String.class, id);
                if (!contentHash.equals(oldHash)) {
                    jdbc.update("UPDATE job_posting SET content_hash = ?, dedupe_key = ?, title = ?, company = ?, location = ?, description = ?,"
                            + " updated_at = ? WHERE id = ?", contentHash, dedupe, p.title(), company, p.location(), p.description(), ts(now), id);
                    updated++;
                    toEnrich.add(id);
                }
            } else if (jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE owner_user_id = ? AND dedupe_key = ?", Integer.class,
                    s.userId, dedupe) > 0) {
                // The same role already exists under another URL (another source, or pasted by hand): keep that one.
                id = jdbc.queryForObject("SELECT MIN(id) FROM job_posting WHERE owner_user_id = ? AND dedupe_key = ?", Long.class, s.userId, dedupe);
            } else {
                try {
                    jdbc.update("INSERT INTO job_posting (owner_user_id, source_id, origin, canonical_url, url_hash, content_hash, dedupe_key,"
                            + " title, company, location, description, first_seen_at, last_seen_at, created_at, updated_at)"
                            + " VALUES (?, ?, 'crawl', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", s.userId, s.id, url, urlHash, contentHash, dedupe,
                            p.title(), company, p.location(), p.description(), ts(now), ts(now), ts(now), ts(now));
                    added++;
                } catch (DuplicateKeyException race) {
                    // Another writer inserted it between our check and insert; the unique constraint kept it single.
                }
                id = jdbc.queryForObject("SELECT id FROM job_posting WHERE owner_user_id = ? AND url_hash = ?", Long.class, s.userId, urlHash);
                toEnrich.add(id);
            }
            seenIds.add(id);
            jdbc.update("UPDATE job_posting SET last_seen_at = ?, missed_runs = 0, status = 'active', closed_at = NULL"
                    + " WHERE id = ? AND source_id = ?", ts(now), id, s.id);
        }
        int culled = cull(s, seenIds, now);
        for (long id : toEnrich) enrich(id);
        return new int[] {seen, added, updated, culled, 0};
    }

    private static String synthesizedUrl(Source s, ExtractedPosting p, String company) {
        String sep = s.url.contains("?") ? "&" : "?";
        return s.url + sep + "posting=" + PostingNormalizer.dedupeKey(company, p.title(), p.location()).substring(0, 16);
    }

    /** Postings of this source missing from the listing: count the miss, then stale, then closed. */
    private int cull(Source s, Set<Long> seenIds, Instant now) {
        int changed = 0;
        List<long[]> candidates = jdbc.query("SELECT id, missed_runs, status FROM job_posting WHERE source_id = ? AND status <> 'closed'",
                (rs, i) -> new long[] {rs.getLong(1), rs.getInt(2), "stale".equals(rs.getString(3)) ? 1 : 0}, s.id);
        for (long[] c : candidates) {
            if (seenIds.contains(c[0])) continue;
            int missed = (int) c[1] + 1;
            if (missed >= closeAfter) {
                jdbc.update("UPDATE job_posting SET missed_runs = ?, status = 'closed', closed_at = ?, updated_at = ? WHERE id = ?",
                        missed, ts(now), ts(now), c[0]);
                changed++;
            } else if (missed >= staleAfter) {
                jdbc.update("UPDATE job_posting SET missed_runs = ?, status = 'stale', updated_at = ? WHERE id = ?", missed, ts(now), c[0]);
                if (c[2] == 0) changed++;
            } else {
                jdbc.update("UPDATE job_posting SET missed_runs = ? WHERE id = ?", missed, c[0]);
            }
        }
        return changed;
    }

    private void enrich(long postingId) {
        if (enricher == null) return;
        try {
            enricher.enrich(postingId);
        } catch (RuntimeException e) {
            log.warn("enrichment of posting {} failed: {}", postingId, e.toString()); // the posting stays, untagged
        }
    }

    private int activeCount(long sourceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE source_id = ? AND status <> 'closed'", Integer.class, sourceId);
    }

    private void finish(long runId, String status, String tier, int seen, int added, int updated, int closed, int unused, String error) {
        jdbc.update("UPDATE crawl_run SET status = ?, finished_at = ?, tier = ?, postings_seen = ?, added = ?, updated = ?, closed = ?, error = ?"
                + " WHERE id = ?", status, ts(clock.get()), "unchanged".equals(tier) ? null : tier, seen, added, updated, closed, error, runId);
    }

    private void touchSource(long sourceId, String tier, String contentHash) {
        jdbc.update("UPDATE job_source SET last_fetched_at = ?, extraction_tier = COALESCE(?, extraction_tier),"
                + " content_hash = COALESCE(?, content_hash) WHERE id = ?", ts(clock.get()), "unchanged".equals(tier) ? null : tier, contentHash, sourceId);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
