package app.resume.jobs.crawl;

import app.resume.jobs.support.StubLlmClient;
import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.PostingNormalizer;
import app.resume.jobs.extract.ExtractionOutputException;
import app.resume.jobs.extract.LlmPostingExtractor;
import app.resume.jobs.fetch.AshbyAdapter;
import app.resume.jobs.fetch.FetchException;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.GreenhouseAdapter;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.fetch.LeverAdapter;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.fetch.RobotsDisallowedException;
import app.resume.jobs.fetch.UrlNotAllowedException;
import app.resume.jobs.support.JobsTestDb;
import app.resume.jobs.validity.PostingValidityChecker;
import app.resume.jobs.validity.PostingValidityService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CrawlLifecycleTests {
    JobsTestDb db;
    long ada;
    long sourceId;
    final Map<String, FetchResult> pages = new HashMap<>();
    final Map<String, RuntimeException> failures = new HashMap<>();
    final List<String> fetched = new ArrayList<>();
    Instant now = Instant.parse("2026-10-20T06:00:00Z");
    CrawlService crawl;
    DailyRun daily;
    String llmOutput;

    final PageFetcher fetcher = url -> {
        fetched.add(url);
        if (failures.containsKey(url)) throw failures.get(url);
        FetchResult r = pages.get(url);
        return r != null ? r : new FetchResult(url, url, 404, "text/html", "");
    };

    static String jsonLd(String... titles) {
        StringBuilder sb = new StringBuilder("<html><head>");
        for (String t : titles) {
            sb.append("<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"").append(t)
                    .append("\",\"hiringOrganization\":\"Acme\",\"url\":\"/jobs/").append(t.toLowerCase().replace(' ', '-'))
                    .append("\",\"description\":\"About ").append(t).append("\"}</script>");
        }
        return sb.append("</head></html>").toString();
    }

    void page(String url, String body) {
        pages.put(url, new FetchResult(url, url, 200, "text/html", body));
    }

    @BeforeEach
    void setUp() {
        db = new JobsTestDb();
        ada = db.user("ada");
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Acme', 'https://acme.example/careers', 'sh')", ada);
        sourceId = db.jdbc.queryForObject("SELECT id FROM job_source", Long.class);
        var llm = new LlmPostingExtractor(StubLlmClient.of((system, user) -> {
            if (llmOutput == null) throw new ExtractionOutputException("no output");
            return llmOutput;
        }));
        crawl = new CrawlService(db.jdbc, fetcher, List.of(new GreenhouseAdapter(), new LeverAdapter(), new AshbyAdapter()), new JsonLdPostingAdapter(),
                llm, null, () -> now, 2, 4);
        daily = new DailyRun(db.jdbc, new PostingValidityService(db.jdbc, new PostingValidityChecker(fetcher)), crawl);
    }

    void nextDay() {
        now = now.plusSeconds(86_400);
    }

    String status(String title) {
        return db.jdbc.queryForObject("SELECT status FROM job_posting WHERE title = ?", String.class, title);
    }

    Map<String, Object> lastRun() {
        return db.jdbc.queryForMap("SELECT * FROM crawl_run ORDER BY id DESC LIMIT 1");
    }

    @Test
    void firstRunAddsPostingsAndRecordsStatsAndTier() {
        page("https://acme.example/careers", jsonLd("SWE Intern", "Data Intern"));
        var r = crawl.crawl(sourceId);

        assertThat(r.outcome()).isEqualTo(CrawlService.Outcome.SUCCEEDED);
        Map<String, Object> run = lastRun();
        assertThat(run.get("status")).isEqualTo("succeeded");
        assertThat(run.get("postings_seen")).isEqualTo(2);
        assertThat(run.get("added")).isEqualTo(2);
        assertThat(run.get("updated")).isEqualTo(0);
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE source_id = ? AND owner_user_id = ? AND origin = 'crawl'",
                Integer.class, sourceId, ada)).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("SELECT canonical_url FROM job_posting WHERE title = 'SWE Intern'", String.class))
                .isEqualTo("https://acme.example/jobs/swe-intern");
        assertThat(db.jdbc.queryForMap("SELECT extraction_tier, last_fetched_at, crawl_lock_until FROM job_source"))
                .containsEntry("extraction_tier", "json_ld").containsEntry("crawl_lock_until", null);
    }

    @Test
    void unchangedPageIsSkippedAndChangedPostingIsUpdated() {
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        crawl.crawl(sourceId);
        nextDay();
        assertThat(crawl.crawl(sourceId).outcome()).isEqualTo(CrawlService.Outcome.SKIPPED_UNCHANGED);
        assertThat(lastRun().get("status")).isEqualTo("skipped_unchanged");
        assertThat(db.jdbc.queryForObject("SELECT missed_runs FROM job_posting", Integer.class)).isZero();

        nextDay();
        page("https://acme.example/careers", jsonLd("SWE Intern").replace("About SWE Intern", "A new description"));
        crawl.crawl(sourceId);
        assertThat(lastRun().get("updated")).isEqualTo(1);
        assertThat(lastRun().get("added")).isEqualTo(0);
        assertThat(db.jdbc.queryForObject("SELECT description FROM job_posting", String.class)).isEqualTo("A new description");
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingPostingsBecomeStaleThenClosedAndComeBackWhenListedAgain() {
        page("https://acme.example/careers", jsonLd("SWE Intern", "Data Intern"));
        crawl.crawl(sourceId);

        page("https://acme.example/careers", jsonLd("SWE Intern", "Other 1"));
        nextDay(); crawl.crawl(sourceId); // Data Intern missed once
        assertThat(status("Data Intern")).isEqualTo("active");
        page("https://acme.example/careers", jsonLd("SWE Intern", "Other 2"));
        nextDay(); crawl.crawl(sourceId); // missed twice: stale
        assertThat(status("Data Intern")).isEqualTo("stale");
        assertThat(status("SWE Intern")).isEqualTo("active");
        assertThat(lastRun().get("closed")).isEqualTo(1); // contract: "became stale or closed in this run"
        page("https://acme.example/careers", jsonLd("SWE Intern", "Other 3"));
        nextDay(); crawl.crawl(sourceId);
        assertThat(status("Data Intern")).isEqualTo("stale");
        page("https://acme.example/careers", jsonLd("SWE Intern", "Other 4"));
        nextDay(); crawl.crawl(sourceId); // missed four times: closed
        assertThat(status("Data Intern")).isEqualTo("closed");
        assertThat(db.jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM job_posting WHERE title = 'Data Intern'", Boolean.class)).isTrue();

        page("https://acme.example/careers", jsonLd("SWE Intern", "Data Intern"));
        nextDay(); crawl.crawl(sourceId);
        assertThat(status("Data Intern")).isEqualTo("active");
        assertThat(db.jdbc.queryForObject("SELECT missed_runs FROM job_posting WHERE title = 'Data Intern'", Integer.class)).isZero();
    }

    @Test
    void theSameRoleAtAnotherUrlOrFromAnIngestIsNotAddedTwice() {
        db.jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company)"
                + " VALUES (?, 'ingest', 'https://handshake.example/9', 'x', 'c', ?, 'SWE Intern', 'Acme')", ada,
                PostingNormalizer.dedupeKey("Acme", "SWE Intern", null));
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        crawl.crawl(sourceId);
        assertThat(lastRun().get("added")).isEqualTo(0);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
    }

    @Test
    void atsFeedSourcesUseTheFeedAndRecordTheTier() throws Exception {
        db.jdbc.update("UPDATE job_source SET url = 'https://jobs.lever.co/acme' WHERE id = ?", sourceId);
        String feed = new String(getClass().getResourceAsStream("/fixtures/lever-feed.json").readAllBytes());
        page("https://api.lever.co/v0/postings/acme?mode=json", feed);
        crawl.crawl(sourceId);
        assertThat(fetched).containsExactly("https://api.lever.co/v0/postings/acme?mode=json");
        assertThat(db.jdbc.queryForObject("SELECT extraction_tier FROM job_source", String.class)).isEqualTo("ats_feed");
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(2);
    }

    @Test
    void llmIsTheFallbackWhenThePageHasNoJsonLd() {
        page("https://acme.example/careers", "<html><body>Intern roles: Robotics Intern in Austin</body></html>");
        llmOutput = "{\"postings\":[{\"title\":\"Robotics Intern\",\"location\":\"Austin\"}]}";
        crawl.crawl(sourceId);
        assertThat(status("Robotics Intern")).isEqualTo("active");
        assertThat(db.jdbc.queryForObject("SELECT extraction_tier FROM job_source", String.class)).isEqualTo("llm");
        // No URL on the posting: a stable synthesized one, so the next run matches it instead of adding it again
        String url = db.jdbc.queryForObject("SELECT canonical_url FROM job_posting", String.class);
        assertThat(url).startsWith("https://acme.example/careers?posting=");
        page("https://acme.example/careers", "<html><body>Intern roles: Robotics Intern in Austin (updated)</body></html>");
        crawl.crawl(sourceId);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
    }

    @Test
    void failuresAreRecordedWithACodeAndNeverTouchPostings() {
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        crawl.crawl(sourceId);

        record Case(RuntimeException failure, String code) {}
        for (Case c : List.of(new Case(new FetchException("timeout"), "fetch_failed"),
                new Case(new UrlNotAllowedException("host is not public"), "url_not_allowed"),
                new Case(new RobotsDisallowedException("https://acme.example/careers"), "robots_disallowed"),
                new Case(new IllegalStateException("boom"), "internal_error"))) {
            nextDay();
            failures.put("https://acme.example/careers", c.failure);
            var r = crawl.crawl(sourceId);
            assertThat(r.outcome()).isEqualTo(CrawlService.Outcome.FAILED);
            assertThat(lastRun().get("status")).isEqualTo("failed");
            assertThat((String) lastRun().get("error")).startsWith(c.code + ":");
            assertThat(status("SWE Intern")).isEqualTo("active");
            assertThat(db.jdbc.queryForObject("SELECT missed_runs FROM job_posting", Integer.class)).isZero();
            assertThat(db.jdbc.queryForObject("SELECT crawl_lock_until IS NULL FROM job_source", Boolean.class)).isTrue(); // lock released
        }
        failures.clear();
        pages.put("https://acme.example/careers", new FetchResult("u", "u", 503, "text/html", ""));
        nextDay();
        crawl.crawl(sourceId);
        assertThat((String) lastRun().get("error")).startsWith("fetch_failed:");
    }

    @Test
    void anEmptyListingDoesNotCullExistingPostings() {
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        crawl.crawl(sourceId);
        for (int i = 0; i < 5; i++) {
            nextDay();
            page("https://acme.example/careers", "<html>site redesign " + i + "</html>");
            llmOutput = "{\"postings\":[]}";
            crawl.crawl(sourceId);
            assertThat((String) lastRun().get("error")).startsWith("empty_listing");
        }
        assertThat(status("SWE Intern")).isEqualTo("active");
    }

    @Test
    void noLlmAndNoJsonLdIsAFailedRunNotACrash() {
        var noLlm = new CrawlService(db.jdbc, fetcher, List.of(), new JsonLdPostingAdapter(), null, null, () -> now, 2, 4);
        page("https://acme.example/careers", "<html>nothing structured</html>");
        assertThat(noLlm.crawl(sourceId).outcome()).isEqualTo(CrawlService.Outcome.FAILED);
        assertThat((String) lastRun().get("error")).startsWith("extraction_failed:");
    }

    @Test
    void aSourceHeldByAnotherRunIsSkippedAndDisabledSourcesAreNotCrawled() {
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        db.jdbc.update("UPDATE job_source SET crawl_lock_until = ?", java.sql.Timestamp.from(now.plusSeconds(60)));
        assertThat(crawl.crawl(sourceId).outcome()).isEqualTo(CrawlService.Outcome.LOCKED);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM crawl_run", Integer.class)).isZero();
        assertThat(fetched).isEmpty();
        // an expired claim (a crashed run) does not block forever
        db.jdbc.update("UPDATE job_source SET crawl_lock_until = ?", java.sql.Timestamp.from(now.minusSeconds(1)));
        assertThat(crawl.crawl(sourceId).outcome()).isEqualTo(CrawlService.Outcome.SUCCEEDED);
        db.jdbc.update("UPDATE job_source SET enabled = FALSE");
        assertThat(crawl.crawl(sourceId).outcome()).isEqualTo(CrawlService.Outcome.LOCKED);
    }

    @Test
    void dailyRunChecksValidityFirstThenCrawlsEnabledSourcesAndIsolatesFailures() {
        db.jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company)"
                + " VALUES (?, 'ingest', 'https://pasted.example/1', 'p', 'c', 'd', 'Pasted', 'X')", ada);
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Broken', 'https://broken.example/', 'b')", ada);
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash, enabled) VALUES (?, 'Off', 'https://off.example/', 'o', FALSE)", ada);
        page("https://pasted.example/1", "<p>Apply</p>");
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        failures.put("https://broken.example/", new FetchException("down"));

        DailyRun.Summary s = daily.run();

        assertThat(fetched.get(0)).isEqualTo("https://pasted.example/1"); // validity first
        assertThat(fetched.indexOf("https://pasted.example/1")).isLessThan(fetched.indexOf("https://acme.example/careers"));
        assertThat(fetched).doesNotContain("https://off.example/");
        assertThat(s.sourcesCrawled()).isEqualTo(1);
        assertThat(s.sourcesFailed()).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM crawl_run", Integer.class)).isEqualTo(2);
        assertThat(status("SWE Intern")).isEqualTo("active");
    }

    @Test
    void validityClosesGonePostingsBeforeTheCrawlSeesThem() {
        page("https://acme.example/careers", jsonLd("SWE Intern"));
        daily.run();
        pages.put("https://acme.example/jobs/swe-intern", new FetchResult("u", "u", 404, "text/html", ""));
        nextDay();
        page("https://acme.example/careers", jsonLd("SWE Intern", "New Role"));
        daily.run();
        // closed by the validity check first, then the crawl still lists it, so it is reopened by the listing
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(2);
    }
}
