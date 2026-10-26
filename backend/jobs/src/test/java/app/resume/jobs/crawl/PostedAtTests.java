package app.resume.jobs.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.GreenhouseAdapter;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.ingest.IngestProcessor;
import app.resume.jobs.support.JobsTestDb;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** US-116: posted_at is stored from each source's own date, never guessed, and a repost never makes a posting look newer. */
class PostedAtTests {
    JobsTestDb db;
    long ada;
    long sourceId;
    final Map<String, String> pages = new HashMap<>();
    Instant now = Instant.parse("2026-10-20T06:00:00Z");
    CrawlService crawl;

    final PageFetcher fetcher = url -> {
        String body = pages.get(url);
        return body == null ? new FetchResult(url, url, 404, "text/html", "") : new FetchResult(url, url, 200, "text/html", body);
    };

    static String jsonLd(String title, String datePosted) {
        return "<script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"hiringOrganization\":\"Acme\","
                + "\"url\":\"/jobs/" + title.toLowerCase().replace(' ', '-') + "\","
                + (datePosted == null ? "" : "\"datePosted\":\"" + datePosted + "\",") + "\"description\":\"About " + title + "\"}</script>";
    }

    Instant postedAt(String title) {
        Timestamp t = db.jdbc.queryForObject("SELECT posted_at FROM job_posting WHERE title = ?", Timestamp.class, title);
        return t == null ? null : t.toInstant();
    }

    @BeforeEach
    void setUp() {
        db = new JobsTestDb();
        ada = db.user("ada");
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Acme', 'https://acme.example/careers', 'sh')", ada);
        sourceId = db.jdbc.queryForObject("SELECT id FROM job_source", Long.class);
        crawl = new CrawlService(db.jdbc, fetcher, List.of(new GreenhouseAdapter()), new JsonLdPostingAdapter(), null, null, () -> now, 2, 4);
    }

    @Test
    void crawlStoresTheSourcesOwnDateOrNull() {
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Dated", "2026-09-01") + jsonLd("Undated", null)
                + jsonLd("Future", "2027-03-01") + jsonLd("Junk", "last tuesday") + jsonLd("Zoned", "2026-09-02T10:00:00-07:00") + "</html>");
        crawl.crawl(sourceId);

        assertThat(postedAt("Dated")).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(postedAt("Undated")).isNull();
        assertThat(postedAt("Future")).isNull();
        assertThat(postedAt("Junk")).isNull();
        assertThat(postedAt("Zoned")).isEqualTo(Instant.parse("2026-09-02T17:00:00Z"));
    }

    @Test
    void greenhouseUsesFirstPublishedNeverUpdatedAt() {
        db.jdbc.update("UPDATE job_source SET url = 'https://boards.greenhouse.io/acme', url_hash = 'gh' WHERE id = ?", sourceId);
        pages.put("https://boards-api.greenhouse.io/v1/boards/acme/jobs?content=true", "{\"jobs\":["
                + "{\"title\":\"Published\",\"absolute_url\":\"https://x.example/1\",\"first_published\":\"2026-04-13T04:49:05-04:00\",\"updated_at\":\"2026-10-01T00:00:00-04:00\"},"
                + "{\"title\":\"Edited Only\",\"absolute_url\":\"https://x.example/2\",\"updated_at\":\"2026-10-01T00:00:00-04:00\"}]}");
        crawl.crawl(sourceId);

        assertThat(postedAt("Published")).isEqualTo(Instant.parse("2026-04-13T08:49:05Z"));
        assertThat(postedAt("Edited Only")).isNull();
    }

    @Test
    void aRepostNeverMakesAnOldPostingLookNewButAnEarlierDateIsTaken() {
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", "2026-08-01") + "</html>");
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));

        now = now.plusSeconds(86_400);
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", "2026-10-15") + "</html>"); // reposted with a later date
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));

        now = now.plusSeconds(86_400);
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", "2026-07-01") + "</html>"); // the source now reports an earlier date
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));

        now = now.plusSeconds(86_400);
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", null) + "</html>"); // the date disappears: keep what we have
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    void aLaterlyAppearingDateFillsAnEmptyColumn() {
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", null) + "</html>");
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isNull();
        now = now.plusSeconds(86_400);
        pages.put("https://acme.example/careers", "<html>" + jsonLd("Role", "2026-09-09") + "</html>");
        crawl.crawl(sourceId);
        assertThat(postedAt("Role")).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));
    }

    @Test
    void ingestStoresJsonLdDateAndAnUnambiguousPostedOnLine() {
        var processor = new IngestProcessor(db.jdbc, fetcher, new JsonLdPostingAdapter(), null, null, () -> now);
        long e1 = event("<html>" + jsonLd("Ingested One", "2026-09-05") + "</html>", "https://acme.example/ingested-one");
        long e2 = event("<html>" + jsonLd("Ingested Two", null).replace("\"description\":\"About Ingested Two\"",
                "\"description\":\"Posted on 2026-09-06. Build things.\"") + "</html>", "https://acme.example/ingested-two");
        processor.process(e1);
        processor.process(e2);

        assertThat(postedAt("Ingested One")).isEqualTo(Instant.parse("2026-09-05T00:00:00Z"));
        assertThat(postedAt("Ingested Two")).isEqualTo(Instant.parse("2026-09-06T00:00:00Z"));
    }

    long event(String html, String hint) {
        db.jdbc.update("INSERT INTO ingest_event (user_id, kind, payload, source_hint, idempotency_key, status) VALUES (?, 'html', ?, ?, ?, 'queued')",
                ada, html, hint, Integer.toHexString(html.hashCode()));
        return db.jdbc.queryForObject("SELECT MAX(id) FROM ingest_event", Long.class);
    }
}
