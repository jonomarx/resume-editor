package app.resume.jobs.crawl;

import app.resume.jobs.validity.PostingValidityService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The daily run: (1) validity first, re-check every existing posting; (2 to 8) then crawl each enabled source. A
 * failing source never stops the others, and a failing validity sweep never stops the crawl.
 */
public class DailyRun {
    public record Summary(PostingValidityService.Summary validity, int sourcesCrawled, int sourcesFailed, int sourcesSkipped) {}

    private static final Logger log = LoggerFactory.getLogger(DailyRun.class);

    private final JdbcTemplate jdbc;
    private final PostingValidityService validity;
    private final CrawlService crawl;

    public DailyRun(JdbcTemplate jdbc, PostingValidityService validity, CrawlService crawl) {
        this.jdbc = jdbc;
        this.validity = validity;
        this.crawl = crawl;
    }

    public Summary run() {
        PostingValidityService.Summary v;
        try {
            v = validity.checkAll();
        } catch (RuntimeException e) {
            log.error("validity sweep failed", e);
            v = new PostingValidityService.Summary(0, 0, 0);
        }
        List<Long> sources = jdbc.queryForList("SELECT id FROM job_source WHERE enabled = TRUE ORDER BY id", Long.class);
        int ok = 0;
        int failed = 0;
        int skipped = 0;
        for (long id : sources) {
            try {
                switch (crawl.crawl(id).outcome()) {
                    case SUCCEEDED, SKIPPED_UNCHANGED -> ok++;
                    case FAILED -> failed++;
                    case LOCKED -> skipped++;
                }
            } catch (RuntimeException e) {
                log.error("crawl of source {} failed outside its run", id, e);
                failed++;
            }
        }
        log.info("daily run: validity {}, sources ok={} failed={} locked={}", v, ok, failed, skipped);
        return new Summary(v, ok, failed, skipped);
    }
}
