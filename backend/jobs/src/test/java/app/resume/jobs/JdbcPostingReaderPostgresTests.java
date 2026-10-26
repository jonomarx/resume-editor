package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.support.JobsPostgresDb;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The reader's SQL (tag EXISTS, LIKE ... ESCAPE, timestamps) on real Postgres. */
@Tag("postgres")
class JdbcPostingReaderPostgresTests {
    @Test
    void everyCriterionWorksOnPostgres() {
        var db = new JobsPostgresDb();
        long ada = db.user("ada");
        long bob = db.user("bob");
        db.jdbc.update("INSERT INTO tag (user_id, name, slug, category) VALUES (?, 'Java', 'java', 'language'), (?, 'Java', 'java', 'language')", ada, bob);
        db.jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company, location,"
                + " description, first_seen_at) VALUES (?, 'crawl', 'u1', 'h1', 'c', 'd1', 'Backend Intern', 'Acme', 'Seattle, WA', '100% remote_work Java', ?),"
                + " (?, 'crawl', 'u2', 'h2', 'c', 'd2', 'Other', 'Globex', NULL, 'SQL', ?), (?, 'crawl', 'u3', 'h3', 'c', 'd3', 'Bob', 'Acme', 'Seattle', 'Java', ?)",
                ada, java.sql.Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")), ada, java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")),
                bob, java.sql.Timestamp.from(Instant.parse("2026-10-20T00:00:00Z")));
        long p1 = db.jdbc.queryForObject("SELECT id FROM job_posting WHERE url_hash = 'h1'", Long.class);
        db.jdbc.update("INSERT INTO job_posting_tag (posting_id, tag_id) SELECT ?, id FROM tag WHERE user_id = ? AND slug = 'java'", p1, ada);
        var reader = new JdbcPostingReader(db.jdbc);

        assertThat(reader.search(ada, PostingQuery.all())).hasSize(2);
        assertThat(reader.search(ada, new PostingQuery(Set.of("java"), null, null, null, null))).extracting(PostingView::id).containsExactly(p1);
        assertThat(reader.search(ada, new PostingQuery(null, "SEATTLE", null, null, null))).hasSize(1);
        assertThat(reader.search(ada, new PostingQuery(null, null, Instant.parse("2026-10-01T00:00:00Z"), null, null))).hasSize(1);
        assertThat(reader.search(ada, new PostingQuery(null, null, null, "acme 100% remote_work", null))).hasSize(1);
        assertThat(reader.search(ada, new PostingQuery(null, null, null, "remote\\_work", null))).isEmpty();
        assertThat(reader.search(ada, new PostingQuery(null, null, null, "50%", null))).isEmpty();
        assertThat(reader.search(bob, PostingQuery.all())).extracting(PostingView::title).containsExactly("Bob");
        assertThat(reader.find(bob, p1)).isEmpty();
    }
}
