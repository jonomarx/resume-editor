package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcPostingReaderTests {
    JdbcTemplate jdbc;
    JdbcPostingReader reader;
    long ada;
    long bob;

    long tag(long user, String name, String slug) {
        jdbc.update("INSERT INTO tag (user_id, name, slug, category) VALUES (?, ?, ?, 'language')", user, name, slug);
        return jdbc.queryForObject("SELECT id FROM tag WHERE user_id = ? AND slug = ?", Long.class, user, slug);
    }

    long posting(long owner, String title, String company, String location, String description, String status, Instant firstSeen) {
        String key = title + owner;
        jdbc.update("INSERT INTO job_posting (owner_user_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company,"
                + " location, description, status, first_seen_at, basic_requirements, preferred_requirements, season)"
                + " VALUES (?, 'crawl', ?, ?, 'c', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", owner, "https://x/" + key, "h" + key, "d" + key, title, company,
                location, description, status, Timestamp.from(firstSeen), "- BS in CS\n* Java\n\n", "Docker", "Summer 2027");
        return jdbc.queryForObject("SELECT id FROM job_posting WHERE url_hash = ?", Long.class, "h" + key);
    }

    void link(long posting, long tag) {
        jdbc.update("INSERT INTO job_posting_tag (posting_id, tag_id) VALUES (?, ?)", posting, tag);
    }

    List<String> titles(long owner, PostingQuery q) {
        return reader.search(owner, q).stream().map(PostingView::title).sorted().toList();
    }

    static PostingQuery q(Set<String> tags, String location, Instant since, String text, Set<Long> closed) {
        return new PostingQuery(tags, location, since, text, closed);
    }

    long a1, a2, a3, a4, b1;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:reader" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/stub", "classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        reader = new JdbcPostingReader(jdbc);
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES ('ada', 'ada', 'x'), ('bob', 'bob', 'x')");
        ada = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'ada'", Long.class);
        bob = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'bob'", Long.class);
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        a1 = posting(ada, "Backend Intern", "Acme", "Seattle, WA", "Build services in Java and Docker", "active", now);
        a2 = posting(ada, "Frontend Intern", "Globex", "Remote (US)", "React and 100% remote_work", "stale", now.minusSeconds(86400L * 10));
        a3 = posting(ada, "Closed Role", "Acme", "Seattle", "Gone", "closed", now);
        a4 = posting(ada, "Data Intern", "Initech", null, "SQL", "active", now.minusSeconds(86400L * 30));
        b1 = posting(bob, "Bob Only Backend", "Acme", "Seattle", "Java", "active", now);
        long java = tag(ada, "Java", "java"), docker = tag(ada, "Docker", "docker"), react = tag(ada, "React", "react");
        long bobJava = tag(bob, "Java", "java");
        link(a1, java); link(a1, docker); link(a2, react); link(a2, java); link(a3, java); link(b1, bobJava);
    }

    @Test
    void searchWithNoFiltersHidesClosedAndOtherUsers() {
        assertThat(titles(ada, PostingQuery.all())).containsExactly("Backend Intern", "Data Intern", "Frontend Intern");
        assertThat(titles(bob, PostingQuery.all())).containsExactly("Bob Only Backend");
    }

    @Test
    void includeClosedIdsKeepsThoseClosedPostingsVisible() {
        assertThat(titles(ada, q(null, null, null, null, Set.of(a3)))).contains("Closed Role");
        assertThat(titles(ada, q(null, null, null, null, Set.of(b1)))).doesNotContain("Bob Only Backend"); // another user's id adds nothing
    }

    @Test
    void aPostingMustCarryEveryTagSlug() {
        assertThat(titles(ada, q(Set.of("java"), null, null, null, null))).containsExactly("Backend Intern", "Frontend Intern");
        assertThat(titles(ada, q(Set.of("java", "docker"), null, null, null, null))).containsExactly("Backend Intern");
        assertThat(titles(ada, q(Set.of("java", "react", "docker"), null, null, null, null))).isEmpty();
        assertThat(titles(ada, q(Set.of("nope"), null, null, null, null))).isEmpty();
    }

    @Test
    void locationIsACaseInsensitiveSubstring() {
        assertThat(titles(ada, q(null, "SEATTLE", null, null, null))).containsExactly("Backend Intern");
        assertThat(titles(ada, q(null, "remote", null, null, null))).containsExactly("Frontend Intern");
        assertThat(titles(ada, q(null, "100%", null, null, null))).isEmpty(); // wildcards in user text are literal
    }

    @Test
    void firstSeenSinceIsInclusive() {
        Instant since = Instant.parse("2026-10-10T00:00:00Z");
        assertThat(titles(ada, q(null, null, since, null, null))).containsExactly("Backend Intern", "Frontend Intern");
        assertThat(titles(ada, q(null, null, Instant.parse("2026-10-20T00:00:00Z"), null, null))).containsExactly("Backend Intern");
    }

    @Test
    void textTermsMustAllOccurInTitleCompanyOrDescription() {
        assertThat(titles(ada, q(null, null, null, "acme java", null))).containsExactly("Backend Intern");
        assertThat(titles(ada, q(null, null, null, "  GLOBEX   react ", null))).containsExactly("Frontend Intern");
        assertThat(titles(ada, q(null, null, null, "java sql", null))).isEmpty();
        assertThat(titles(ada, q(null, null, null, "remote_work", null))).containsExactly("Frontend Intern");
        assertThat(titles(ada, q(null, null, null, "remote\\_work", null))).isEmpty();
        assertThat(titles(ada, q(null, null, null, "'; DROP TABLE job_posting; --", null))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isEqualTo(5);
    }

    @Test
    void findReturnsAnyStatusButOnlyForTheOwner() {
        assertThat(reader.find(ada, a3)).get().extracting(PostingView::closed).isEqualTo(true);
        assertThat(reader.find(bob, a1)).isEmpty();
        assertThat(reader.find(ada, b1)).isEmpty();
        assertThat(reader.find(ada, 99999)).isEmpty();
    }

    @Test
    void viewHasSlugsRequirementListsAndFields() {
        PostingView v = reader.find(ada, a1).orElseThrow();
        assertThat(v.tags()).containsExactly("docker", "java");
        assertThat(v.basicRequirements()).containsExactly("BS in CS", "Java");
        assertThat(v.preferredRequirements()).containsExactly("Docker");
        assertThat(v.season()).isEqualTo("Summer 2027");
        assertThat(v.status()).isEqualTo("active");
        assertThat(v.origin()).isEqualTo("crawl");
        assertThat(v.sourceId()).isNull();
        assertThat(v.postedAt()).isNull();
        assertThat(v.firstSeenAt()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(v.description()).contains("Java");
        assertThat(reader.find(ada, a4).orElseThrow().location()).isNull();
        assertThat(reader.find(ada, a4).orElseThrow().tags()).isEmpty();
    }

    @Test
    void postedAtIsExposedWhenStored() {
        jdbc.update("UPDATE job_posting SET posted_at = ? WHERE id = ?", Timestamp.from(Instant.parse("2026-09-01T10:00:00Z")), a1);
        assertThat(reader.find(ada, a1).orElseThrow().postedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(reader.find(ada, a2).orElseThrow().postedAt()).isNull();
    }

    @Test
    void anotherUsersTagWithTheSameSlugDoesNotLeak() {
        assertThat(reader.find(bob, b1).orElseThrow().tags()).containsExactly("java");
        assertThat(titles(bob, q(Set.of("docker"), null, null, null, null))).isEmpty();
    }
}
