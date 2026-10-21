package app.resume.jobs.validity;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.fetch.FetchException;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.fetch.RobotsDisallowedException;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostingValidityTests {
    final Map<String, FetchResult> pages = new HashMap<>();
    final Map<String, RuntimeException> failures = new HashMap<>();
    final PageFetcher fetcher = url -> {
        if (failures.containsKey(url)) throw failures.get(url);
        return pages.get(url);
    };
    final PostingValidityChecker checker = new PostingValidityChecker(fetcher);

    FetchResult page(String url, int status, String body) {
        FetchResult r = new FetchResult(url, url, status, "text/html", body);
        pages.put(url, r);
        return r;
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void closedOnGoneStatuses(int status) {
        page("https://x/1", status, "");
        assertThat(checker.check("https://x/1").kind()).isEqualTo(Verdict.Kind.CLOSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<p>Sorry, we are <b>no longer accepting</b> applications for this role.</p>",
            "<div>This job is no longer available.</div>",
            "<h1>Position has been filled</h1><p>This position has been filled.</p>",
            "<p>THIS   POSTING\nHAS EXPIRED</p>"})
    void closedWhenPageSaysSo(String html) {
        page("https://x/1", 200, "<html><body>" + html + "</body></html>");
        // markup inside a phrase (the first case) still reads as one phrase in the visible text
        assertThat(checker.check("https://x/1").kind()).isEqualTo(Verdict.Kind.CLOSED);
    }

    @Test
    void phraseInScriptIsIgnoredAndLivePageIsOpen() {
        page("https://x/1", 200, "<html><body><h1>Intern</h1><script>var s='this job has expired'</script>Apply now</body></html>");
        assertThat(checker.check("https://x/1").kind()).isEqualTo(Verdict.Kind.OPEN);
    }

    @Test
    void notClosedWhenUncertain() {
        page("https://x/500", 503, "no longer accepting applications");
        page("https://x/403", 403, "");
        page("https://x/429", 429, "");
        failures.put("https://x/net", new FetchException("boom"));
        failures.put("https://x/robots", new RobotsDisallowedException("https://x/robots"));
        for (String u : new String[] {"https://x/500", "https://x/403", "https://x/429", "https://x/net", "https://x/robots"}) {
            assertThat(checker.check(u).kind()).as(u).isEqualTo(Verdict.Kind.UNKNOWN);
        }
    }

    // ---- service, on H2 ----

    JdbcTemplate jdbc;

    @BeforeEach
    void db() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:validity" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/stub", "classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES ('ada', 'ada', 'x')");
    }

    long posting(String url, String origin, String status) {
        jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key,"
                + " title, company, status) VALUES ((SELECT id FROM app_user), ?, ?, ?, 'c', 'd', 'Intern', 'Acme', ?)",
                origin, url, url, status);
        return jdbc.queryForObject("SELECT id FROM job_posting WHERE url_hash = ?", Long.class, url);
    }

    String status(long id) {
        return jdbc.queryForObject("SELECT status FROM job_posting WHERE id = ?", String.class, id);
    }

    @Test
    void sweepClosesGonePostingsCrawledAndIngestedAndLeavesTheRest() {
        long live = posting("https://x/live", "crawl", "active");
        long gone = posting("https://x/gone", "crawl", "active");
        long stale = posting("https://x/stale-gone", "crawl", "stale");
        long pasted = posting("https://x/pasted", "ingest", "active");
        long flaky = posting("https://x/flaky", "crawl", "active");
        long already = posting("https://x/closed", "crawl", "closed");
        page("https://x/live", 200, "<p>Apply</p>");
        page("https://x/gone", 404, "");
        page("https://x/stale-gone", 410, "");
        page("https://x/pasted", 200, "<p>We are no longer accepting applications.</p>");
        failures.put("https://x/flaky", new FetchException("timeout"));

        var svc = new PostingValidityService(jdbc, checker);
        PostingValidityService.Summary s = svc.checkAll();

        assertThat(s).isEqualTo(new PostingValidityService.Summary(5, 3, 1));
        assertThat(status(live)).isEqualTo("active");
        assertThat(status(gone)).isEqualTo("closed");
        assertThat(status(stale)).isEqualTo("closed");
        assertThat(status(pasted)).isEqualTo("closed");
        assertThat(status(flaky)).isEqualTo("active");
        assertThat(status(already)).isEqualTo("closed");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM job_posting WHERE id = ?", Boolean.class, gone)).isTrue();
        // closed postings are not re-checked
        assertThat(svc.checkAll().checked()).isEqualTo(2);
    }

    @Test
    void aCheckerCrashOnOneRowDoesNotStopTheSweep() {
        long boom = posting("https://x/boom", "crawl", "active");
        long gone = posting("https://x/gone", "crawl", "active");
        page("https://x/gone", 404, "");
        failures.put("https://x/boom", new IllegalStateException("bug"));
        var s = new PostingValidityService(jdbc, checker).checkAll();
        assertThat(s.closed()).isEqualTo(1);
        assertThat(status(boom)).isEqualTo("active");
        assertThat(status(gone)).isEqualTo("closed");
    }
}
