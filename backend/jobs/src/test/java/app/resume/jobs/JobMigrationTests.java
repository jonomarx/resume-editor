package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Applies the jobs migrations on H2 (PostgreSQL mode). The two core tables they reference (app_user, tag) come from
 * copies in db/stub (test resources), because jobs must not depend on core; the app module's context tests run the
 * real combined migration set.
 */
class JobMigrationTests {
    JdbcTemplate jdbc;
    long userId;

    @BeforeEach
    void migrate() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:jobs" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/stub", "classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES ('ada', 'ada', 'x')");
        userId = jdbc.queryForObject("SELECT id FROM app_user", Long.class);
    }

    long posting(String urlHash) {
        jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key,"
                + " title, company) VALUES (?, 'crawl', 'https://x/1', ?, 'c', 'd', 'Intern', 'Acme')", userId, urlHash);
        return jdbc.queryForObject("SELECT id FROM job_posting WHERE url_hash = ?", Long.class, urlHash);
    }

    @Test
    void allTablesExist() {
        for (String t : new String[] {"job_source", "job_posting", "job_posting_tag", "crawl_run", "ingest_event"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)).isZero();
        }
    }

    @Test
    void postingIsUniquePerOwnerAndUrlHash() {
        posting("h1");
        assertThatThrownBy(() -> posting("h1")).isInstanceOf(DataIntegrityViolationException.class);
        posting("h2"); // a different url hash is fine
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES ('bob', 'bob', 'x')");
        long bob = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'bob'", Long.class);
        jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key,"
                + " title, company) VALUES (?, 'ingest', 'https://x/1', 'h1', 'c', 'd', 'Intern', 'Acme')", bob);
    }

    @Test
    void statusAndOriginAreConstrained() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash,"
                + " content_hash, dedupe_key, title, company, status) VALUES (?, 'crawl', 'u', 'h', 'c', 'd', 't', 'c',"
                + " 'bogus')", userId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postingTagsCascadeAndIngestEventsAreIdempotentPerKey() {
        long p = posting("h1");
        jdbc.update("INSERT INTO tag (user_id, name, slug, category) VALUES (?, 'Java', 'java', 'language')", userId);
        long tag = jdbc.queryForObject("SELECT id FROM tag", Long.class);
        jdbc.update("INSERT INTO job_posting_tag (posting_id, tag_id) VALUES (?, ?)", p, tag);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO job_posting_tag (posting_id, tag_id) VALUES (?, ?)", p, tag))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM job_posting WHERE id = ?", p);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posting_tag", Integer.class)).isZero();

        jdbc.update("INSERT INTO ingest_event (user_id, kind, idempotency_key) VALUES (?, 'url', 'k')", userId);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ingest_event (user_id, kind, idempotency_key) VALUES (?, 'html', 'k')", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourceUrlIsUniquePerUser() {
        String sql = "INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, 'Acme', 'https://a', 'u1')";
        jdbc.update(sql, userId);
        assertThatThrownBy(() -> jdbc.update(sql, userId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
