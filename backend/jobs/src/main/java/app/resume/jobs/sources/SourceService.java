package app.resume.jobs.sources;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.fetch.PublicHostPolicy;
import app.resume.jobs.fetch.UrlNotAllowedException;
import app.resume.jobs.web.ApiException;
import app.resume.jobs.web.ApiProblem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Career page sources, always scoped to one user. A source of another user does not exist as far as callers can tell. */
@Service
public class SourceService {
    public record Source(long id, String name, String url, boolean enabled, String extractionTier, Instant createdAt, Instant lastCrawlAt) {}

    static final String COLUMNS = "id, name, url, enabled, extraction_tier, created_at, last_fetched_at";
    static final RowMapper<Source> MAPPER = (rs, i) -> new Source(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
            rs.getBoolean("enabled"), rs.getString("extraction_tier"),
            rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
            rs.getObject("last_fetched_at", java.time.OffsetDateTime.class) == null ? null
                    : rs.getObject("last_fetched_at", java.time.OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;
    private final PublicHostPolicy hostPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public SourceService(JdbcTemplate jdbc, PublicHostPolicy hostPolicy) {
        this.jdbc = jdbc;
        this.hostPolicy = hostPolicy;
    }

    public List<Source> list(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM job_source WHERE user_id = ? ORDER BY created_at, id", MAPPER, userId);
    }

    public Source get(long userId, long id) {
        return find(userId, id).orElseThrow(ApiException::notFound);
    }

    private Optional<Source> find(long userId, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM job_source WHERE id = ? AND user_id = ?", MAPPER, id, userId).stream().findFirst();
    }

    public Source create(long userId, String rawUrl, String rawName) {
        if (rawUrl == null || rawUrl.isBlank()) throw ApiException.validation("url", "is required");
        if (rawUrl.length() > 2000) throw ApiException.validation("url", "must be at most 2000 characters");
        String url;
        try {
            url = PostingNormalizer.canonicalUrl(rawUrl);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("url", "must be an absolute http or https URL");
        }
        try {
            hostPolicy.checkLiteral(url);
        } catch (UrlNotAllowedException e) {
            throw ApiException.validation("url", "must be the URL of a public website");
        }
        String name = rawName == null ? URI.create(url).getHost() : rawName.strip();
        if (name.isEmpty() || name.length() > 100) throw ApiException.validation("name", "must be 1 to 100 characters");
        String hash = PostingNormalizer.urlHash(url);
        try {
            // The unique (user_id, url_hash) constraint is the final guard against two concurrent adds.
            jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, ?, ?, ?)", userId, name, url, hash);
        } catch (DuplicateKeyException e) {
            long existing = jdbc.queryForObject("SELECT id FROM job_source WHERE user_id = ? AND url_hash = ?", Long.class, userId, hash);
            throw new ApiException(HttpStatus.CONFLICT, ApiProblem.of("source_exists", "You already added this career page")
                    .with("existingSourceId", existing));
        }
        long id = jdbc.queryForObject("SELECT id FROM job_source WHERE user_id = ? AND url_hash = ?", Long.class, userId, hash);
        return get(userId, id);
    }

    public Source update(long userId, long id, String name, Boolean enabled) {
        get(userId, id); // 404 for unknown or foreign ids
        if (name != null) {
            String n = name.strip();
            if (n.isEmpty() || n.length() > 100) throw ApiException.validation("name", "must be 1 to 100 characters");
            jdbc.update("UPDATE job_source SET name = ? WHERE id = ? AND user_id = ?", n, id, userId);
        }
        if (enabled != null) jdbc.update("UPDATE job_source SET enabled = ? WHERE id = ? AND user_id = ?", enabled, id, userId);
        return get(userId, id);
    }

    /** Removes the source and its crawl runs; its postings stay and lose their source (FK actions). */
    public void delete(long userId, long id) {
        if (jdbc.update("DELETE FROM job_source WHERE id = ? AND user_id = ?", id, userId) == 0) throw ApiException.notFound();
    }
}
