package app.resume.jobs.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.crawl.CrawlService;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A crawl and an ingest finding the same posting URL at the same moment keep exactly one row (unique (owner, url_hash)). */
@Tag("postgres")
class CrawlVersusIngestPostgresTests {
    static String page(int n) {
        return "<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"Intern " + n + "\",\"hiringOrganization\":\"Acme\","
                + "\"url\":\"https://acme.example/jobs/" + n + "\"}</script>";
    }

    @Test
    void concurrentCrawlAndIngestOfTheSameUrlLeaveOnePosting() throws Exception {
        var db = new JobsPostgresDb();
        long ada = db.user("ada");
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Acme', 'https://acme.example/careers', 'h')", ada);
        long sourceId = db.jdbc.queryForObject("SELECT id FROM job_source", Long.class);
        int rounds = 25;
        var fetcher = (app.resume.jobs.fetch.PageFetcher) url -> {
            if (url.equals("https://acme.example/careers")) {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < rounds; i++) all.append(page(i));
                return new FetchResult(url, url, 200, "text/html", all.toString());
            }
            int n = Integer.parseInt(url.substring(url.lastIndexOf('/') + 1));
            return new FetchResult(url, url, 200, "text/html", page(n));
        };
        var crawl = new CrawlService(db.jdbc, fetcher, List.of(), new JsonLdPostingAdapter(), null, null, Instant::now, 2, 4);
        var processor = new IngestProcessor(db.jdbc, fetcher, new JsonLdPostingAdapter(), null, null, Instant::now);
        var service = new IngestService(db.jdbc, new app.resume.jobs.fetch.PublicHostPolicy(), Runnable::run, processor, Instant::now, 10_000);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Ingest events for every URL are created first (processed by the pool's workers), then both race.
            List<Long> events = new ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                db.jdbc.update("INSERT INTO ingest_event (user_id, kind, idempotency_key, status, payload) VALUES (?, 'url', ?, 'queued', ?)",
                        ada, "k" + i, "https://acme.example/jobs/" + i);
                events.add(db.jdbc.queryForObject("SELECT id FROM ingest_event WHERE idempotency_key = ?", Long.class, "k" + i));
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            fs.add(pool.submit(() -> {
                go.await();
                return crawl.crawl(sourceId).outcome();
            }));
            for (int w = 0; w < 3; w++) {
                int worker = w;
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = worker; i < rounds; i += 3) processor.process(events.get(i));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(rounds);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(DISTINCT url_hash) FROM job_posting", Integer.class)).isEqualTo(rounds);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM ingest_event WHERE status = 'done' AND posting_id IS NOT NULL", Integer.class)).isEqualTo(rounds);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM crawl_run WHERE status = 'succeeded'", Integer.class)).isEqualTo(1);
    }

    @Test
    void simultaneousIdenticalRequestsCreateOneEvent() throws Exception {
        var db = new JobsPostgresDb();
        long ada = db.user("ada");
        var fetcher = (app.resume.jobs.fetch.PageFetcher) url -> new FetchResult(url, url, 200, "text/html", page(1));
        var processor = new IngestProcessor(db.jdbc, fetcher, new JsonLdPostingAdapter(), null, null, Instant::now);
        var service = new IngestService(db.jdbc, new app.resume.jobs.fetch.PublicHostPolicy(), Runnable::run, processor, Instant::now, 10_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<IngestService.Accepted>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> {
                go.await();
                return service.submit(ada, "url", "https://acme.example/jobs/1", null);
            }));
            go.countDown();
            List<IngestService.Accepted> results = new ArrayList<>();
            for (var f : fs) results.add(f.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().map(IngestService.Accepted::ingestEventId).distinct()).hasSize(1);
            assertThat(results.stream().filter(a -> !a.duplicate())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM ingest_event", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(1);
    }
}
