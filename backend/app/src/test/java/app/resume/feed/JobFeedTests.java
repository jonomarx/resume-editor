package app.resume.feed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import app.resume.jobs.PostingView;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** US-050: the jobs endpoints of contract 0.6.0 against dummy posting data (DummyPostingReader). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dummy"})
class JobFeedTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DummyPostingReader reader;

    record U(Cookie session, long id) {}

    U newUser() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Cookie c = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
        String body = mvc.perform(get("/api/me").cookie(c)).andReturn().getResponse().getContentAsString();
        return new U(c, Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1")));
    }

    /** Gives the user's master a text node tagged with each slug (creating the tags). */
    void masterTags(U u, String... slugs) throws Exception {
        mvc.perform(get("/api/resumes/master").cookie(u.session())).andExpect(status().isOk());
        long resume = jdbc.queryForObject("select id from resume where user_id = ? and kind = 'MASTER'", Long.class, u.id());
        for (String slug : slugs) {
            mvc.perform(CsrfSupport.post("/api/tags").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + slug + "\",\"category\":\"language\"}")).andExpect(status().is2xxSuccessful());
            long tag = jdbc.queryForObject("select id from tag where user_id = ? and slug = ?", Long.class, u.id(), slug);
            jdbc.update("insert into resume_node_tag (user_id, resume_id, node_id, tag_id) values (?, ?, ?, ?)",
                    u.id(), resume, "n-" + slug, tag);
        }
    }

    static PostingView posting(long id, String title, String company, String location, String desc, List<String> tags,
            String status, Instant firstSeen) {
        return new PostingView(id, title, company, location, "https://x.example/" + id, desc, List.of("basic"), List.of("pref"),
                tags, "Summer 2027", null, null, status, "crawl", 1L, null, firstSeen, firstSeen);
    }

    ResultActions list(U u, String... params) throws Exception {
        var req = get("/api/jobs").cookie(u.session());
        for (int i = 0; i < params.length; i += 2) req = req.param(params[i], params[i + 1]);
        return mvc.perform(req);
    }

    U seeded() throws Exception {
        U u = newUser();
        Instant now = Instant.now();
        reader.replace(u.id(), List.of(
                posting(1, "Java Intern", "Acme", "Seattle, WA", "Backend work", List.of("java", "postgresql"), "active", now.minus(1, ChronoUnit.DAYS)),
                posting(2, "React Intern", "Globex", "Remote", "Frontend work", List.of("react"), "active", now.minus(10, ChronoUnit.DAYS)),
                posting(3, "Java Data Intern", "Initech", "Austin, TX", "Analytics", List.of("java"), "stale", now.minus(3, ChronoUnit.DAYS)),
                posting(4, "Old Intern", "Hooli", "Seattle, WA", "Gone", List.of("java"), "closed", now.minus(2, ChronoUnit.DAYS))));
        return u;
    }

    @Test
    void requiresSignIn() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/jobs/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsPageShapeAndHidesClosed() throws Exception {
        U u = seeded();
        masterTags(u, "java");
        list(u).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0)).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(3)) // fit 100, ties by id
                .andExpect(jsonPath("$.items[0].fitScore").value(100))
                .andExpect(jsonPath("$.items[0].userStatus").value("new"))
                .andExpect(jsonPath("$.items[0].postingStatus").value("stale"))
                .andExpect(jsonPath("$.items[0].draftId").value((Object) null))
                .andExpect(jsonPath("$.items[1].id").value(1)).andExpect(jsonPath("$.items[1].fitScore").value(50))
                .andExpect(jsonPath("$.items[2].id").value(2)).andExpect(jsonPath("$.items[2].fitScore").value(0));
    }

    @Test
    void fitIsZeroWhenMasterHasNoTags() throws Exception {
        U u = seeded();
        list(u).andExpect(jsonPath("$.items[*].fitScore").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(0))));
    }

    @Test
    void sortNewestAndPaging() throws Exception {
        U u = seeded();
        list(u, "sort", "newest").andExpect(jsonPath("$.items[0].id").value(1)).andExpect(jsonPath("$.items[1].id").value(3))
                .andExpect(jsonPath("$.items[2].id").value(2));
        list(u, "sort", "newest", "limit", "1", "offset", "1").andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(3))
                .andExpect(jsonPath("$.limit").value(1)).andExpect(jsonPath("$.offset").value(1));
        list(u, "offset", "10").andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void filtersByTagsLocationFreshnessAndText() throws Exception {
        U u = seeded();
        list(u, "tag", "java").andExpect(jsonPath("$.total").value(2));
        list(u, "tag", "java", "tag", "postgresql").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
        list(u, "location", "SEATTLE").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
        list(u, "maxAgeDays", "5").andExpect(jsonPath("$.total").value(2));
        list(u, "q", "frontend globex").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(2));
        list(u, "q", "nothing-matches").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void statusFilterAndSetStatus() throws Exception {
        U u = seeded();
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"saved\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.userStatus").value("saved"));
        // setting the current value again is a no-op
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"saved\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.userStatus").value("saved"));
        mvc.perform(CsrfSupport.put("/api/jobs/2/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"dismissed\"}")).andExpect(status().isOk());
        list(u, "status", "saved").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(1));
        list(u, "status", "new").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(3));
        // back to new removes the saved mark
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"new\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.userStatus").value("new"));
        list(u, "status", "saved").andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/jobs/2").cookie(u.session())).andExpect(jsonPath("$.userStatus").value("dismissed"));
    }

    @Test
    void statusIsPerUser() throws Exception {
        U a = seeded();
        U b = seeded();
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(a.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"applied\"}")).andExpect(status().isOk());
        list(b, "status", "applied").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void detailHasFullDescriptionEligibilityAndFit() throws Exception {
        U u = seeded();
        masterTags(u, "java");
        mvc.perform(get("/api/jobs/1").cookie(u.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fullDescription").value("Backend work"))
                .andExpect(jsonPath("$.eligibility.season").value("Summer 2027"))
                .andExpect(jsonPath("$.eligibility.workAuthorization").value((Object) null))
                .andExpect(jsonPath("$.fit.matchedTags[0]").value("java"))
                .andExpect(jsonPath("$.fit.missingTags[0]").value("postgresql"))
                .andExpect(jsonPath("$.fitScore").value(50)).andExpect(jsonPath("$.basicRequirements[0]").value("basic"));
        mvc.perform(get("/api/jobs/999").cookie(u.session())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    void briefDescriptionIsCappedAt500() throws Exception {
        U u = newUser();
        reader.replace(u.id(), List.of(posting(7, "Long", "Acme", null, "x".repeat(900), List.of(), "active", Instant.now())));
        list(u).andExpect(jsonPath("$.items[0].description").value("x".repeat(500)));
        mvc.perform(get("/api/jobs/7").cookie(u.session())).andExpect(jsonPath("$.fullDescription").value("x".repeat(900)));
    }

    @Test
    void draftLinksAndKeepsItsClosedPostingVisible() throws Exception {
        U u = seeded();
        jdbc.update("insert into resume (user_id, kind, job_posting_id, version, content_hash, storage_uri) "
                + "values (?, 'DRAFT', 4, 1, 'h', 'u')", u.id());
        long draft = jdbc.queryForObject("select id from resume where user_id = ? and kind = 'DRAFT'", Long.class, u.id());
        list(u).andExpect(jsonPath("$.total").value(4));
        list(u, "q", "gone").andExpect(jsonPath("$.items[0].draftId").value(draft));
    }

    @Test
    void validatesParameters() throws Exception {
        U u = seeded();
        for (String[] bad : new String[][] {{"sort", "x"}, {"status", "bogus"}, {"maxAgeDays", "0"}, {"limit", "0"},
                {"limit", "101"}, {"offset", "-1"}, {"limit", "abc"}}) {
            list(u, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        }
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"bogus\"}")).andExpect(status().isBadRequest());
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(CsrfSupport.put("/api/jobs/999/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"saved\"}")).andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/jobs/1/status").cookie(u.session())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"saved\"}")).andExpect(status().isForbidden());
    }

    @Test
    void readsNeverCreateTheMasterResume() throws Exception {
        U u = seeded();
        list(u).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].fitScore").value(0));
        mvc.perform(get("/api/jobs/1").cookie(u.session())).andExpect(status().isOk()).andExpect(jsonPath("$.fitScore").value(0));
        mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(u.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"saved\"}")).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("select count(*) from resume where user_id = ?", Integer.class, u.id())).isZero();
    }

    @Test
    void anotherUsersPostingAndStatusAre404() throws Exception {
        U a = newUser();
        U b = newUser();
        reader.replace(a.id(), List.of(posting(50, "Mine", "Acme", null, "d", List.of(), "active", Instant.now())));
        reader.replace(b.id(), List.of());
        mvc.perform(get("/api/jobs/50").cookie(a.session())).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/50").cookie(b.session())).andExpect(status().isNotFound());
        mvc.perform(CsrfSupport.put("/api/jobs/50/status").cookie(b.session()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"saved\"}")).andExpect(status().isNotFound());
        list(b).andExpect(jsonPath("$.total").value(0));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("select count(*) from job_user_status where user_id = ?", Integer.class, b.id())).isZero();
    }
}
