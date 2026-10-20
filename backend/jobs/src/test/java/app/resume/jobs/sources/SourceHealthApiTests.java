package app.resume.jobs.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.jobs.fetch.PublicHostPolicy;
import app.resume.jobs.support.JobsTestDb;
import app.resume.jobs.web.ApiException;
import app.resume.jobs.web.JobsApiAdvice;
import app.resume.jobs.web.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SourceHealthApiTests {
    JobsTestDb db;
    long ada;
    long bob;
    long current;
    MockMvc mvc;
    Instant t = Instant.parse("2026-10-20T06:00:00Z");

    @BeforeEach
    void setUp() {
        db = new JobsTestDb();
        ada = db.user("ada");
        bob = db.user("bob");
        current = ada;
        UserContext users = new UserContext() {
            @Override public long requireUserId() { return requireSessionUserId(); }
            @Override public long requireSessionUserId() {
                if (current == 0) throw ApiException.unauthorized();
                return current;
            }
        };
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var sources = new SourceService(db.jdbc, new PublicHostPolicy());
        mvc = MockMvcBuilders.standaloneSetup(new SourceHealthController(new SourceHealthService(db.jdbc, sources), users))
                .setControllerAdvice(new JobsApiAdvice()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    long source(long user, String name) {
        db.jdbc.update("INSERT INTO job_source (user_id, name, url, url_hash) VALUES (?, ?, ?, ?)", user, name, "https://" + name + ".example/", name);
        return db.jdbc.queryForObject("SELECT id FROM job_source WHERE url_hash = ?", Long.class, name);
    }

    void run(long user, long source, int minute, String status, int seen, int added, int updated, int closed, String error) {
        db.jdbc.update("INSERT INTO crawl_run (user_id, source_id, status, started_at, finished_at, postings_seen, added, updated, closed, error)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", user, source, status, Timestamp.from(t.plusSeconds(60L * minute)),
                status.equals("running") ? null : Timestamp.from(t.plusSeconds(60L * minute + 9)), seen, added, updated, closed, error);
    }

    void posting(long user, long source, String url, String status) {
        db.jdbc.update("INSERT INTO job_posting (owner_user_id, source_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company, status)"
                + " VALUES (?, ?, 'crawl', ?, ?, 'c', ?, 'T', 'C', ?)", user, source, url, url, url, status);
    }

    @Test
    void stateAndFailureCountFollowTheLatestRuns() throws Exception {
        long never = source(ada, "never");
        long healthy = source(ada, "healthy");
        long degraded = source(ada, "degraded");
        long failing = source(ada, "failing");
        long recovered = source(ada, "recovered");
        run(ada, healthy, 1, "failed", 0, 0, 0, 0, "fetch_failed: x");
        run(ada, healthy, 2, "succeeded", 13, 2, 1, 0, null);
        run(ada, degraded, 1, "succeeded", 5, 5, 0, 0, null);
        run(ada, degraded, 2, "failed", 0, 0, 0, 0, "fetch_failed: x");
        run(ada, failing, 1, "succeeded", 5, 5, 0, 0, null);
        run(ada, failing, 2, "failed", 0, 0, 0, 0, "url_not_allowed: x");
        run(ada, failing, 3, "failed", 0, 0, 0, 0, "fetch_failed: x");
        run(ada, failing, 4, "running", 0, 0, 0, 0, null); // in progress: does not break the streak
        run(ada, recovered, 1, "failed", 0, 0, 0, 0, "x");
        run(ada, recovered, 2, "failed", 0, 0, 0, 0, "x");
        run(ada, recovered, 3, "skipped_unchanged", 0, 0, 0, 0, null);
        posting(ada, healthy, "https://h/1", "active");
        posting(ada, healthy, "https://h/2", "active");
        posting(ada, healthy, "https://h/3", "stale");
        posting(ada, healthy, "https://h/4", "closed");

        String body = mvc.perform(get("/api/source-health")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/source-health"))
                .andExpect(jsonPath("$[0].source.name").value("never")).andExpect(jsonPath("$[0].state").value("never_run"))
                .andExpect(jsonPath("$[0].lastRun").isEmpty()).andExpect(jsonPath("$[0].recentRuns.length()").value(0))
                .andExpect(jsonPath("$[1].state").value("healthy")).andExpect(jsonPath("$[1].consecutiveFailures").value(0))
                .andExpect(jsonPath("$[1].activePostings").value(2)).andExpect(jsonPath("$[1].stalePostings").value(1))
                .andExpect(jsonPath("$[1].lastRun.status").value("succeeded")).andExpect(jsonPath("$[1].lastRun.postingsSeen").value(13))
                .andExpect(jsonPath("$[1].lastRun.added").value(2)).andExpect(jsonPath("$[1].lastRun.updated").value(1))
                .andExpect(jsonPath("$[1].lastRun.error").isEmpty()).andExpect(jsonPath("$[1].recentRuns.length()").value(2))
                .andExpect(jsonPath("$[1].recentRuns[1].status").value("failed")) // newest first
                .andExpect(jsonPath("$[1].lastRun.startedAt").value("2026-10-20T06:02:00Z"))
                .andExpect(jsonPath("$[2].state").value("degraded")).andExpect(jsonPath("$[2].consecutiveFailures").value(1))
                .andExpect(jsonPath("$[3].state").value("failing")).andExpect(jsonPath("$[3].consecutiveFailures").value(2))
                .andExpect(jsonPath("$[3].lastRun.status").value("running")).andExpect(jsonPath("$[3].lastRun.finishedAt").isEmpty())
                .andExpect(jsonPath("$[4].state").value("healthy")); // a skipped_unchanged run ends the failure streak
        assertThat(body).contains("\"extractionTier\"");
    }

    @Test
    void onlyTheLastTenRunsAreReturned() throws Exception {
        long s = source(ada, "busy");
        for (int i = 0; i < 14; i++) run(ada, s, i, "succeeded", i, 0, 0, 0, null);
        mvc.perform(get("/api/source-health")).andExpect(jsonPath("$[0].recentRuns.length()").value(10))
                .andExpect(jsonPath("$[0].recentRuns[0].postingsSeen").value(13)).andExpect(jsonPath("$[0].recentRuns[9].postingsSeen").value(4));
    }

    @Test
    void otherUsersSourcesAndRunsAreInvisible() throws Exception {
        long mine = source(ada, "mine");
        long theirs = source(bob, "theirs");
        run(ada, mine, 1, "succeeded", 1, 1, 0, 0, null);
        run(bob, theirs, 1, "failed", 0, 0, 0, 0, "secret");
        current = bob;
        mvc.perform(get("/api/source-health")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].source.name").value("theirs"));
        current = ada;
        mvc.perform(get("/api/source-health")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].state").value("healthy"));
        current = 0;
        mvc.perform(get("/api/source-health")).andExpect(status().isUnauthorized());
    }
}
