package app.resume.jobs.validity;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step 1 of the daily run: re-check every active or stale posting (crawled and ingested alike) and close the ones
 * that are gone. The caller (the daily run, US-035) wires the fetcher; nothing here crawls or adds postings.
 */
public class PostingValidityService {
    public record Summary(int checked, int closed, int unknown) {}

    private record Row(long id, String url) {}

    private static final Logger log = LoggerFactory.getLogger(PostingValidityService.class);

    private final JdbcTemplate jdbc;
    private final PostingValidityChecker checker;

    public PostingValidityService(JdbcTemplate jdbc, PostingValidityChecker checker) {
        this.jdbc = jdbc;
        this.checker = checker;
    }

    public Summary checkAll() {
        List<Row> rows = jdbc.query("SELECT id, canonical_url FROM job_posting WHERE status <> 'closed' ORDER BY id",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2)));
        int closed = 0;
        int unknown = 0;
        for (Row row : rows) {
            Verdict v;
            try {
                v = checker.check(row.url);
            } catch (RuntimeException e) { // one bad row must not stop the sweep
                v = Verdict.unknown(String.valueOf(e));
            }
            switch (v.kind()) {
                case CLOSED -> {
                    // Guarded update: a concurrent close or crawl cannot be overwritten twice.
                    int n = jdbc.update("UPDATE job_posting SET status = 'closed', closed_at = CURRENT_TIMESTAMP,"
                            + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status <> 'closed'", row.id);
                    if (n > 0) {
                        closed++;
                        log.info("posting {} closed ({})", row.id, v.reason());
                    }
                }
                case UNKNOWN -> {
                    unknown++;
                    log.debug("posting {} not verifiable ({})", row.id, v.reason());
                }
                case OPEN -> { }
            }
        }
        return new Summary(rows.size(), closed, unknown);
    }
}
