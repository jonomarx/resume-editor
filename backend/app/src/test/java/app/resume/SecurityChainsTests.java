package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;

/**
 * The jobs endpoints through the real security chains: core's session chain (sessions, CSRF, 401/403 bodies) and the Job
 * List Manager's ingest-token chain at Order(1). The jobs module's own API tests use a stand-in for the session chain;
 * this is the only test of the real pairing. Skipped while the jobs endpoints are not part of the build (they reach this
 * branch with the epic merge); it needs no change when they do.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityChainsTests {
    @Autowired MockMvc mvc;

    @BeforeEach
    void jobsEndpointsArePresent() {
        Assumptions.assumeTrue(ClassUtils.isPresent("app.resume.jobs.ingest.IngestController", getClass().getClassLoader()),
                "jobs endpoints are not in this build yet");
    }

    static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    record User(Cookie session, long id) {}

    User newUser() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
        String me = mvc.perform(get("/api/me").cookie(s)).andReturn().getResponse().getContentAsString();
        return new User(s, Long.parseLong(me.replaceAll(".*\"id\":(\\d+).*", "$1")));
    }

    static long idOf(String json) {
        Matcher m = Pattern.compile("\"(?:id|ingestEventId)\":(\\d+)").matcher(json);
        assertThat(m.find()).as(json).isTrue();
        return Long.parseLong(m.group(1));
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as(json).isTrue();
        return m.group(1);
    }

    MockHttpServletResponse run(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse();
    }

    MockHttpServletResponse sessionPost(User u, String url, String body) throws Exception {
        return run(CsrfSupport.post(url).cookie(u.session()).contentType(JSON).content(body));
    }

    /** Creates an ingest token for the user; returns {tokenId, secret}. */
    String[] newToken(User u) throws Exception {
        String body = sessionPost(u, "/api/ingest/tokens", "{\"label\":\"t\"}").getContentAsString();
        return new String[] {String.valueOf(idOf(body)), field(body, "token")};
    }

    MockHttpServletRequestBuilder bearerPost(String secret, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ingest")
                .header("Authorization", "Bearer " + secret).contentType(JSON).content(body);
    }

    static final String TEXT = "{\"kind\":\"text\",\"payload\":\"Software Intern at Acme, Seattle\"}";

    // ---- session requests ----

    @Test
    void aSessionRequestWithoutTheCsrfHeaderIs403CsrfInvalid() throws Exception {
        User u = newUser();
        for (String url : new String[] {"/api/sources", "/api/ingest", "/api/ingest/tokens"}) {
            MockHttpServletResponse res = run(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                    .cookie(u.session()).contentType(JSON).content("{}"));
            assertThat(res.getStatus()).as(url).isEqualTo(403);
            assertThat(res.getContentAsString()).as(url).contains("\"code\":\"csrf_invalid\"");
        }
        assertThat(run(delete("/api/sources/1").cookie(u.session())).getStatus()).isEqualTo(403);
    }

    @Test
    void withoutASessionTheJobsEndpointsAre401Unauthorized() throws Exception {
        for (MockHttpServletRequestBuilder b : new MockHttpServletRequestBuilder[] {
                get("/api/sources"), get("/api/source-health"), get("/api/ingest/events"), get("/api/ingest/tokens"),
                CsrfSupport.post("/api/sources").contentType(JSON).content("{}"),
                CsrfSupport.post("/api/ingest").contentType(JSON).content(TEXT)}) {
            MockHttpServletResponse res = run(b);
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("\"code\":\"unauthorized\"");
            assertThat(res.getHeader("Access-Control-Allow-Origin")).as("session chain is never CORS-enabled").isNull();
        }
    }

    @Test
    void aSignedInSessionWithCsrfUsesTheEndpointsAsItsOwnUser() throws Exception {
        User u = newUser();
        assertThat(sessionPost(u, "/api/sources", "{\"url\":\"https://acme.example/careers\",\"name\":\"Acme\"}").getStatus()).isEqualTo(201);
        assertThat(run(get("/api/sources").cookie(u.session())).getContentAsString()).contains("acme.example");
        MockHttpServletResponse accepted = sessionPost(u, "/api/ingest", TEXT);
        assertThat(accepted.getStatus()).isEqualTo(202);
        assertThat(accepted.getHeader("Access-Control-Allow-Origin")).as("a session request on ingest is never CORS-enabled").isNull();
        assertThat(accepted.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    // ---- bearer ingest ----

    @Test
    void aValidBearerTokenIngestsWithoutSessionOrCsrfAndGetsCorsHeaders() throws Exception {
        User u = newUser();
        String secret = newToken(u)[1];
        MockHttpServletResponse res = run(bearerPost(secret, TEXT));
        assertThat(res.getStatus()).isEqualTo(202);
        assertThat(res.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(res.getHeader("Access-Control-Allow-Credentials")).isNull();
        long eventId = idOf(res.getContentAsString());
        MockHttpServletResponse event = run(get("/api/ingest/events/" + eventId).header("Authorization", "Bearer " + secret));
        assertThat(event.getStatus()).isEqualTo(200);
        assertThat(event.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(run(get("/api/ingest/events").header("Authorization", "Bearer " + secret)).getStatus()).isEqualTo(200);
    }

    @Test
    void noCredentialABadTokenAndARevokedTokenGetTheSame401() throws Exception {
        User u = newUser();
        String[] token = newToken(u);
        MockHttpServletResponse none = run(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ingest")
                .contentType(JSON).content(TEXT));
        assertThat(none.getStatus()).isEqualTo(401);
        MockHttpServletResponse unknown = run(bearerPost("ing_doesnotexist", TEXT));
        MockHttpServletResponse malformed = run(bearerPost("   ", TEXT));
        assertThat(sessionDelete(u, "/api/ingest/tokens/" + token[0]).getStatus()).isEqualTo(204);
        MockHttpServletResponse revoked = run(bearerPost(token[1], TEXT));
        for (MockHttpServletResponse r : new MockHttpServletResponse[] {unknown, malformed, revoked}) {
            assertThat(r.getStatus()).isEqualTo(401);
            assertThat(r.getContentAsString()).isEqualTo(none.getContentAsString());
            assertThat(r.getHeader("Access-Control-Allow-Origin")).as("the 401 carries the CORS headers too").isEqualTo("*");
        }
    }

    MockHttpServletResponse sessionDelete(User u, String url) throws Exception {
        return run(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                .cookie(u.session()).cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN)).header("X-XSRF-TOKEN", CsrfSupport.TOKEN));
    }

    @Test
    void aBearerTokenIsJudgedAloneNeverMixedWithTheSession() throws Exception {
        User a = newUser();
        User b = newUser();
        String secretOfA = newToken(a)[1];
        // a bad token with a perfectly valid session is still 401: the cookie is never a fallback
        MockHttpServletResponse bad = run(bearerPost("ing_wrong", TEXT).cookie(b.session()));
        assertThat(bad.getStatus()).isEqualTo(401);
        // a good token acts as its own owner whatever session is attached
        long eventId = idOf(run(bearerPost(secretOfA, TEXT).cookie(b.session())).getContentAsString());
        assertThat(run(get("/api/ingest/events/" + eventId).cookie(a.session())).getStatus()).isEqualTo(200);
        assertThat(run(get("/api/ingest/events/" + eventId).cookie(b.session())).getStatus()).isEqualTo(404);
    }

    @Test
    void aBearerTokenIsRejectedOnEveryOtherApiRouteWithoutCorsHeaders() throws Exception {
        User u = newUser();
        String secret = newToken(u)[1];
        for (MockHttpServletRequestBuilder b : new MockHttpServletRequestBuilder[] {
                get("/api/me"), get("/api/sources"), get("/api/ingest/tokens"), get("/api/resumes/master"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/ingest/tokens")
                        .contentType(JSON).content("{\"label\":\"x\"}")}) {
            MockHttpServletResponse r = run(b.header("Authorization", "Bearer " + secret).cookie(u.session()));
            assertThat(r.getStatus()).isEqualTo(401);
            assertThat(r.getContentAsString()).contains("\"code\":\"unauthorized\"");
            assertThat(r.getHeader("Access-Control-Allow-Origin")).isNull();
        }
    }

    @Test
    void corsPreflightIsAnsweredForIngestOnly() throws Exception {
        MockHttpServletResponse ok = run(options("/api/ingest").header("Origin", "https://jobs.example")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization"));
        assertThat(ok.getStatus()).isEqualTo(204);
        assertThat(ok.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(ok.getHeader("Access-Control-Allow-Headers")).contains("Authorization");
        assertThat(ok.getHeader("Access-Control-Allow-Credentials")).isNull();
        for (String other : new String[] {"/api/sources", "/api/me", "/api/ingest/tokens", "/api/resumes/master"}) {
            MockHttpServletResponse r = run(options(other).header("Origin", "https://jobs.example")
                    .header("Access-Control-Request-Method", "POST"));
            assertThat(r.getHeader("Access-Control-Allow-Origin")).as(other).isNull();
            assertThat(r.getStatus()).as(other).isNotEqualTo(204);
        }
    }

    // ---- ownership ----

    @Test
    void anotherUsersResourcesAre404NotForbidden() throws Exception {
        User a = newUser();
        User b = newUser();
        long sourceId = idOf(sessionPost(a, "/api/sources", "{\"url\":\"https://owned-by-a.example/jobs\",\"name\":\"A\"}").getContentAsString());
        String[] tokenOfA = newToken(a);
        long eventId = idOf(sessionPost(a, "/api/ingest", TEXT).getContentAsString());

        assertThat(run(get("/api/sources/" + sourceId).cookie(b.session())).getStatus()).isEqualTo(404);
        assertThat(run(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/sources/" + sourceId)
                .cookie(b.session()).cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN)).header("X-XSRF-TOKEN", CsrfSupport.TOKEN)
                .contentType(JSON).content("{\"enabled\":false}")).getStatus()).isEqualTo(404);
        assertThat(sessionDelete(b, "/api/sources/" + sourceId).getStatus()).isEqualTo(404);
        assertThat(run(get("/api/sources").cookie(b.session())).getContentAsString()).doesNotContain("owned-by-a.example");

        assertThat(run(get("/api/ingest/events/" + eventId).cookie(b.session())).getStatus()).isEqualTo(404);
        assertThat(run(get("/api/ingest/events").cookie(b.session())).getContentAsString()).doesNotContain("\"id\":" + eventId + ",");
        assertThat(sessionDelete(b, "/api/ingest/tokens/" + tokenOfA[0]).getStatus()).isEqualTo(404);
        assertThat(run(get("/api/ingest/tokens").cookie(b.session())).getContentAsString()).doesNotContain("\"id\":" + tokenOfA[0] + ",");

        // the owner still sees everything, and the token that B could not revoke still works
        assertThat(run(get("/api/sources/" + sourceId).cookie(a.session())).getStatus()).isEqualTo(200);
        assertThat(run(bearerPost(tokenOfA[1], TEXT)).getStatus()).isEqualTo(202);
    }
}
