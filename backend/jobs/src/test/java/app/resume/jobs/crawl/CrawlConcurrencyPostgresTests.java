package app.resume.jobs.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.extract.LlmPostingExtractor;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.support.JobsPostgresDb;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Concurrent runs on one source (DESIGN section 8: lock per source; unique (owner, url_hash) as the final guard). */
@Tag("postgres")
class CrawlConcurrencyPostgresTests {
    static final String PAGE = "<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"SWE Intern\","
            + "\"hiringOrganization\":\"Acme\",\"url\":\"/jobs/1\"}</script>";

    @Test
    void ofManySimultaneousRunsOnOneSourceExactlyOneCrawls() throws Exception {
        var db = new JobsPostgresDb();
        long ada = db.user("ada");
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Acme', 'https://acme.example/careers', 'h')", ada);
        long sourceId = db.jdbc.queryForObject("SELECT id FROM job_source", Long.class);

        int threads = 8;
        CountDownLatch inFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        var crawl = new CrawlService(db.jdbc, url -> {
            fetches.incrementAndGet();
            inFetch.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new FetchResult(url, url, 200, "text/html", PAGE);
        }, List.of(), new JsonLdPostingAdapter(), null, null, Instant::now, 2, 4);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<CrawlService.Outcome>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> crawl.crawl(sourceId).outcome()));
            assertThat(inFetch.await(10, TimeUnit.SECONDS)).isTrue(); // the first run holds the lock inside its fetch
            for (int i = 1; i < threads; i++) futures.add(pool.submit(() -> crawl.crawl(sourceId).outcome()));
            List<CrawlService.Outcome> early = new ArrayList<>();
            for (int i = 1; i < threads; i++) early.add(futures.get(i).get(10, TimeUnit.SECONDS)); // the rest finish while the first is blocked
            release.countDown();
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)).isEqualTo(CrawlService.Outcome.SUCCEEDED);
            assertThat(early).hasSize(threads - 1).containsOnly(CrawlService.Outcome.LOCKED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fetches).hasValue(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM crawl_run", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT crawl_lock_until IS NULL FROM job_source", Boolean.class)).isTrue();
    }

    @Test
    void simultaneousRunsOnDifferentSourcesWithTheSamePostingKeepOnePosting() throws Exception {
        var db = new JobsPostgresDb();
        long ada = db.user("ada");
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'A', 'https://a.example/', 'a')", ada);
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'B', 'https://b.example/', 'b')", ada);
        List<Long> ids = db.jdbc.queryForList("SELECT id FROM job_source ORDER BY id", Long.class);
        var crawl = new CrawlService(db.jdbc, url -> new FetchResult(url, url, 200, "text/html",
                PAGE.replace("/jobs/1", "https://shared.example/jobs/1") + url), List.of(), new JsonLdPostingAdapter(), null, null, Instant::now, 2, 4);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (long id : ids) fs.add(pool.submit(() -> {
                go.await();
                return crawl.crawl(id).outcome();
            }));
            go.countDown();
            for (Future<?> f : fs) assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(CrawlService.Outcome.SUCCEEDED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
    }
}
