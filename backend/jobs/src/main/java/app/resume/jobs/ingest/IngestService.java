package app.resume.jobs.ingest;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.fetch.PublicHostPolicy;
import app.resume.jobs.fetch.UrlNotAllowedException;
import app.resume.jobs.web.ApiException;
import app.resume.jobs.web.ApiProblem;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Accepts ingest requests (202 at once, processing later) and reads ingest events. Everything is scoped to one user. */
public class IngestService {
    public record Accepted(long ingestEventId, String status, boolean duplicate) {}

    public record EventError(String code, String message) {}

    public record Event(long id, String kind, String status, Instant receivedAt, Instant finishedAt, Long jobId, EventError error) {}

    public static final int MAX_PAYLOAD_CHARS = 1_000_000;
    static final Set<String> KINDS = Set.of("url", "html", "text", "email");
    private static final RowMapper<Event> MAPPER = (rs, i) -> {
        OffsetDateTime finished = rs.getObject("processed_at", OffsetDateTime.class);
        long job = rs.getLong("posting_id");
        boolean noJob = rs.wasNull();
        String code = rs.getString("error_code");
        return new Event(rs.getLong("id"), rs.getString("kind"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), finished == null ? null : finished.toInstant(), noJob ? null : job,
                code == null ? null : new EventError(code, rs.getString("error_message")));
    };
    private static final String EVENT_COLUMNS = "id, kind, status, created_at, processed_at, posting_id, error_code, error_message";

    private final JdbcTemplate jdbc;
    private final PublicHostPolicy hostPolicy;
    private final Executor executor;
    private final IngestProcessor processor;
    private final Supplier<Instant> clock;
    private final int ratePerMinute;
    private final Map<Long, ArrayDeque<Instant>> recent = new HashMap<>();

    public IngestService(JdbcTemplate jdbc, PublicHostPolicy hostPolicy, Executor executor, IngestProcessor processor,
            Supplier<Instant> clock, int ratePerMinute) {
        this.jdbc = jdbc;
        this.hostPolicy = hostPolicy;
        this.executor = executor;
        this.processor = processor;
        this.clock = clock;
        this.ratePerMinute = ratePerMinute;
    }

    public Accepted submit(long userId, String kind, String payload, String sourceHint) {
        if (kind == null || !KINDS.contains(kind)) throw ApiException.validation("kind", "must be one of url, html, text, email");
        if (payload == null || payload.isBlank()) throw ApiException.validation("payload", "is required");
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiProblem.of("too_large", "The posting is too large"));
        }
        if (sourceHint != null && sourceHint.length() > 500) throw ApiException.validation("sourceHint", "must be at most 500 characters");
        String key;
        if (kind.equals("url")) {
            String canonical;
            try {
                canonical = PostingNormalizer.canonicalUrl(payload);
                hostPolicy.checkLiteral(canonical);
            } catch (IllegalArgumentException | UrlNotAllowedException e) {
                throw ApiException.validation("payload", "must be an absolute http or https URL of a public website");
            }
            key = PostingNormalizer.sha256("url:" + canonical);
            payload = canonical;
        } else {
            key = PostingNormalizer.sha256("content:" + payload.strip());
        }
        checkRate(userId);

        List<Event> existing = jdbc.query("SELECT " + EVENT_COLUMNS + " FROM ingest_event WHERE user_id = ? AND idempotency_key = ?", MAPPER, userId, key);
        if (!existing.isEmpty()) return new Accepted(existing.get(0).id(), existing.get(0).status(), true);
        try {
            // unique (user_id, idempotency_key) is the final guard when two identical requests arrive together
            jdbc.update("INSERT INTO ingest_event (user_id, kind, source_hint, idempotency_key, status, payload, created_at) VALUES (?, ?, ?, ?, 'queued', ?, ?)",
                    userId, kind, sourceHint == null || sourceHint.isBlank() ? null : sourceHint.strip(), key, payload, Timestamp.from(clock.get()));
        } catch (DuplicateKeyException race) {
            Event e = jdbc.queryForObject("SELECT " + EVENT_COLUMNS + " FROM ingest_event WHERE user_id = ? AND idempotency_key = ?", MAPPER, userId, key);
            return new Accepted(e.id(), e.status(), true);
        }
        long id = jdbc.queryForObject("SELECT id FROM ingest_event WHERE user_id = ? AND idempotency_key = ?", Long.class, userId, key);
        executor.execute(() -> processor.process(id));
        return new Accepted(id, "queued", false);
    }

    private synchronized void checkRate(long userId) {
        Instant now = clock.get();
        ArrayDeque<Instant> q = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) q.pollFirst();
        if (q.size() >= ratePerMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ApiProblem.of("rate_limited", "Slow down and try again shortly"));
        }
        q.addLast(now);
    }

    public List<Event> list(long userId, int limit) {
        int n = Math.max(1, Math.min(100, limit));
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM ingest_event WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT " + n, MAPPER, userId);
    }

    public Event get(long userId, long id) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM ingest_event WHERE id = ? AND user_id = ?", MAPPER, id, userId).stream().findFirst()
                .orElseThrow(ApiException::notFound);
    }
}
