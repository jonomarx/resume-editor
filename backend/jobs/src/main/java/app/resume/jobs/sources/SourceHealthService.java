package app.resume.jobs.sources;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** Per-source stats from crawl runs (contract `SourceHealth`). */
@Service
public class SourceHealthService {
    public record CrawlRun(long id, Instant startedAt, Instant finishedAt, String status, int postingsSeen, int added, int updated,
            int closed, String error) {}

    public record SourceHealth(SourceService.Source source, String state, int consecutiveFailures, int activePostings,
            int stalePostings, CrawlRun lastRun, List<CrawlRun> recentRuns) {}

    static final int RECENT_RUNS = 10;

    private static final RowMapper<CrawlRun> RUN = (rs, i) -> {
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new CrawlRun(rs.getLong("id"), rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                finished == null ? null : finished.toInstant(), rs.getString("status"), rs.getInt("postings_seen"),
                rs.getInt("added"), rs.getInt("updated"), rs.getInt("closed"), rs.getString("error"));
    };

    private final JdbcTemplate jdbc;
    private final SourceService sources;

    public SourceHealthService(JdbcTemplate jdbc, SourceService sources) {
        this.jdbc = jdbc;
        this.sources = sources;
    }

    public List<SourceHealth> forUser(long userId) {
        return sources.list(userId).stream().map(s -> health(userId, s)).toList();
    }

    private SourceHealth health(long userId, SourceService.Source s) {
        List<CrawlRun> recent = jdbc.query("SELECT id, started_at, finished_at, status, postings_seen, added, updated, closed, error"
                + " FROM crawl_run WHERE source_id = ? AND user_id = ? ORDER BY started_at DESC, id DESC LIMIT " + RECENT_RUNS,
                RUN, s.id(), userId);
        int failures = 0;
        for (CrawlRun r : recent) {
            if (r.status().equals("running")) continue; // in progress: neither a failure nor a recovery
            if (!r.status().equals("failed")) break;
            failures++;
        }
        String state = recent.isEmpty() ? "never_run" : failures >= 2 ? "failing" : failures == 1 ? "degraded" : "healthy";
        int active = count(s.id(), "active");
        int stale = count(s.id(), "stale");
        return new SourceHealth(s, state, failures, active, stale, recent.isEmpty() ? null : recent.get(0), recent);
    }

    private int count(long sourceId, String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE source_id = ? AND status = ?", Integer.class, sourceId, status);
    }
}
