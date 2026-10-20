package app.resume.jobs.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.jobs.fetch.FetchException;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.JsonLdPostingAdapter;
import app.resume.jobs.fetch.PageFetcher;
import app.resume.jobs.fetch.PublicHostPolicy;
import app.resume.jobs.fetch.UrlNotAllowedException;
import app.resume.jobs.support.JobsTestDb;
import app.resume.jobs.web.IngestSecurityConfig;
import app.resume.jobs.web.JobsApiAdvice;
import app.resume.jobs.web.SecurityUserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The ingest endpoints behind a real Spring Security setup: our ingest-token chain first, then a stand-in for core's
 * session chain (session cookie, CSRF on, JSON 401), so the bearer, CORS, CSRF and session rules are exercised together.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = IngestApiTests.TestConfig.class)
class IngestApiTests {
    static final String ARTICLE = "<html><head><script type=\"application/ld+json\">{\"@type\":\"JobPosting\",\"title\":\"SWE Intern\","
            + "\"hiringOrganization\":\"Acme\",\"url\":\"https://acme.example/jobs/1\"}</script></head></html>";
    static final JobsTestDb DB = new JobsTestDb();
    static final Map<String, FetchResult> PAGES = new HashMap<>();
    static final AtomicReference<RuntimeException> FETCH_FAILURE = new AtomicReference<>();
    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({IngestSecurityConfig.class, JobsApiAdvice.class, SecurityUserContext.class})
    static class TestConfig implements WebMvcConfigurer {
        @Bean ObjectMapper mapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new MappingJackson2HttpMessageConverter(mapper()));
        }
        @Bean org.springframework.jdbc.core.JdbcTemplate jdbc() { return DB.jdbc; }
        @Bean IngestTokenService tokens() { return new IngestTokenService(DB.jdbc, NOW::get); }
        @Bean IngestProcessor processor() {
            PageFetcher fetcher = url -> {
                if (FETCH_FAILURE.get() != null) throw FETCH_FAILURE.get();
                FetchResult r = PAGES.get(url);
                return r != null ? r : new FetchResult(url, url, 404, "text/html", "");
            };
            return new IngestProcessor(DB.jdbc, fetcher, new JsonLdPostingAdapter(), null, null, NOW::get);
        }
        @Bean IngestService service() {
            return new IngestService(DB.jdbc, new PublicHostPolicy(), Runnable::run, processor(), NOW::get, 6);
        }
        @Bean IngestController controller(SecurityUserContext users) { return new IngestController(service(), tokens(), users); }

        /** Stand-in for core's session chain: sign-in required, CSRF on, JSON 401. */
        @Bean @Order(2) SecurityFilterChain sessionChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
            http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(401);
                        res.setContentType("application/json");
                        mapper.writeValue(res.getOutputStream(), app.resume.jobs.web.ApiProblem.of("unauthorized", "Sign in required"));
                    }));
            return http.build();
        }
    }

    @Autowired WebApplicationContext ctx;
    @Autowired IngestTokenService tokenService;
    MockMvc mvc;
    long ada;
    long bob;
    int seq;

    @BeforeEach
    void setUp() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        for (String t : List.of("ingest_event", "ingest_token", "job_posting", "app_user")) DB.jdbc.update("DELETE FROM " + t);
        ada = DB.user("ada");
        bob = DB.user("bob");
        PAGES.clear();
        FETCH_FAILURE.set(null);
        NOW.set(Instant.parse("2026-10-20T10:00:00Z").plusSeconds(3600L * ++seq)); // a fresh rate-limit window per test
    }

    MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        SecurityContext c = new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                new User(username, "x", List.of()), null, List.of()));
        s.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, c);
        return s;
    }

    String newToken(String user) throws Exception {
        String body = mvc.perform(post("/api/ingest/tokens").session(session(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Bookmarklet\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder bearerPost(String token, String json) {
        return post("/api/ingest").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    static String json(String kind, String payload) {
        return "{\"kind\":\"" + kind + "\",\"payload\":" + new ObjectMapper().valueToTree(payload) + "}";
    }

    // ---- tokens ----

    @Test
    void tokensAreCreatedListedAndRevokedWithASessionOnly() throws Exception {
        String t = newToken("ada");
        assertThat(t).startsWith("ing_").hasSizeGreaterThan(30);
        mvc.perform(post("/api/ingest/tokens").session(session("ada")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Second\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andExpect(jsonPath("$.label").value("Second"))
                .andExpect(jsonPath("$.revokedAt").isEmpty()).andExpect(jsonPath("$.lastUsedAt").isEmpty());
        String list = mvc.perform(get("/api/ingest/tokens").session(session("ada"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].label").value("Second")) // newest first
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(t).doesNotContain("token_hash").doesNotContain("\"token\"");
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM ingest_token WHERE token_hash = ?", Integer.class, t)).isZero(); // only a hash is stored

        mvc.perform(post("/api/ingest/tokens").session(session("ada")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("label"));
        mvc.perform(post("/api/ingest/tokens").session(session("ada")).contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}"))
                .andExpect(status().isForbidden()); // CSRF
        mvc.perform(get("/api/ingest/tokens")).andExpect(status().isUnauthorized());

        long id = DB.jdbc.queryForObject("SELECT id FROM ingest_token WHERE label = 'Bookmarklet'", Long.class);
        mvc.perform(delete("/api/ingest/tokens/" + id).session(session("bob")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/ingest/tokens/" + id).session(session("ada")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/ingest/tokens/" + id).session(session("ada")).with(csrf())).andExpect(status().isNoContent()); // no-op
        mvc.perform(get("/api/ingest/tokens").session(session("ada"))).andExpect(jsonPath("$[1].revokedAt").isString());
    }

    @Test
    void aBearerTokenCannotManageTokens() throws Exception {
        String t = newToken("ada");
        mvc.perform(get("/api/ingest/tokens").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ingest/tokens").header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/ingest/tokens/1").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    // ---- bearer ingest ----

    @Test
    void bearerIngestIsAcceptedWithCorsAndNoCsrfThenProcessed() throws Exception {
        String t = newToken("ada");
        PAGES.put("https://acme.example/jobs/1", new FetchResult("u", "https://acme.example/jobs/1", 200, "text/html", ARTICLE));
        MvcResult r = mvc.perform(bearerPost(t, json("url", "https://ACME.example/jobs/1?utm_source=li")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued")).andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Access-Control-Allow-Credentials"))
                .andReturn();
        long eventId = Long.parseLong(r.getResponse().getContentAsString().replaceAll(".*\"ingestEventId\":(\\d+).*", "$1"));
        mvc.perform(get("/api/ingest/events/" + eventId).header("Authorization", "Bearer " + t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done")).andExpect(jsonPath("$.kind").value("url")).andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.jobId").isNumber()).andExpect(jsonPath("$.finishedAt").isString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"));
        Map<String, Object> posting = DB.jdbc.queryForMap("SELECT * FROM job_posting");
        assertThat(posting).containsEntry("origin", "ingest").containsEntry("title", "SWE Intern").containsEntry("canonical_url", "https://acme.example/jobs/1");
        assertThat(posting.get("source_id")).isNull();
        assertThat(posting.get("owner_user_id")).isEqualTo(ada);
        assertThat(DB.jdbc.queryForObject("SELECT last_used_at IS NOT NULL FROM ingest_token", Boolean.class)).isTrue();
        mvc.perform(get("/api/ingest/events").header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void everyBadCredentialGetsTheSameUniform401() throws Exception {
        String revoked = newToken("ada");
        DB.jdbc.update("UPDATE ingest_token SET revoked_at = CURRENT_TIMESTAMP");
        String good = newToken("bob");
        List<String> bodies = new java.util.ArrayList<>();
        for (String header : new String[] {null, "Bearer ing_0000000000000000000000000000000000000000000000", "Bearer " + revoked, "Bearer ", "Bearer garbage",
                "Bearer " + good.substring(0, good.length() - 1), "Bearer " + "x".repeat(5000), "Basic abc"}) {
            var req = post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content(json("text", "hello"));
            if (header != null) req = req.header("Authorization", header);
            if (header == null || !header.startsWith("Bearer")) req = req.with(csrf()); // a session-style request: CSRF passes, sign-in is missing
            MvcResult res = mvc.perform(req).andExpect(status().isUnauthorized()).andReturn();
            bodies.add(res.getResponse().getContentAsString());
            if (header != null && header.startsWith("Bearer")) {
                assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).as(header).isEqualTo("*");
                assertThat(res.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
            }
        }
        assertThat(bodies).containsOnly("{\"code\":\"unauthorized\",\"message\":\"Sign in required\"}");
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM ingest_event", Integer.class)).isZero();
    }

    @Test
    void aRevokedTokenIsRejectedAtOnce() throws Exception {
        String t = newToken("ada");
        mvc.perform(bearerPost(t, json("text", "hello world"))).andExpect(status().isAccepted());
        long id = DB.jdbc.queryForObject("SELECT id FROM ingest_token", Long.class);
        mvc.perform(delete("/api/ingest/tokens/" + id).session(session("ada")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(bearerPost(t, json("text", "hello again"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ingest/events").header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized());
    }

    @Test
    void aBearerRequestIgnoresTheSessionAndAValidTokenWorksOnlyOnIngestRoutes() throws Exception {
        String good = newToken("ada");
        // a valid session cookie does not rescue a bad bearer token
        mvc.perform(post("/api/ingest").session(session("ada")).with(csrf()).header("Authorization", "Bearer ing_nope")
                .contentType(MediaType.APPLICATION_JSON).content(json("text", "hello"))).andExpect(status().isUnauthorized());
        // a good token on another route is 401 even with a valid session, and carries no CORS headers there
        MvcResult other = mvc.perform(get("/api/sources").session(session("ada")).header("Authorization", "Bearer " + good)).andExpect(status().isUnauthorized()).andReturn();
        assertThat(other.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        mvc.perform(get("/api/jobs").header("Authorization", "Bearer " + good)).andExpect(status().isUnauthorized());
        // the good token acts as its own owner whatever session is attached
        mvc.perform(post("/api/ingest").session(session("bob")).header("Authorization", "Bearer " + good).contentType(MediaType.APPLICATION_JSON)
                .content(json("text", "hello"))).andExpect(status().isAccepted());
        assertThat(DB.jdbc.queryForObject("SELECT user_id FROM ingest_event", Long.class)).isEqualTo(ada);
    }

    @Test
    void aSuccessfulBearerRequestNeverCreatesRotatesOrReadsASession() throws Exception {
        String t = newToken("ada");
        MockHttpSession bobs = session("bob");
        String before = bobs.getId();
        MvcResult r = mvc.perform(get("/api/ingest/events").session(bobs).header("Authorization", "Bearer " + t)).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(bobs.getId()).as("session id rotated by the bearer request").isEqualTo(before);
        assertThat(bobs.isInvalid()).isFalse();
        // the same, for a POST that is accepted, and for a request that carries no session at all
        mvc.perform(post("/api/ingest").session(bobs).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                .content(json("text", "hello"))).andExpect(status().isAccepted());
        assertThat(bobs.getId()).isEqualTo(before);
        MvcResult noSession = mvc.perform(get("/api/ingest/events").header("Authorization", "Bearer " + t)).andExpect(status().isOk()).andReturn();
        assertThat(noSession.getRequest().getSession(false)).as("a bearer request must not create a session").isNull();
        // the attached session still works as bob afterwards
        mvc.perform(get("/api/ingest/events").session(bobs)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // ---- CORS and sessions ----

    @Test
    void preflightIsAnsweredForIngestOnly() throws Exception {
        mvc.perform(options("/api/ingest").header("Origin", "https://jobs.example").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Methods", "POST, OPTIONS"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Headers", "Authorization, Content-Type"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Max-Age", "600"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Access-Control-Allow-Credentials"));
        for (String path : new String[] {"/api/sources", "/api/ingest/events", "/api/ingest/tokens", "/api/jobs"}) {
            MvcResult r = mvc.perform(options(path).header("Origin", "https://jobs.example").header("Access-Control-Request-Method", "POST")).andReturn();
            assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).as(path).isNull();
            assertThat(r.getResponse().getStatus()).as(path).isNotEqualTo(204);
        }
    }

    @Test
    void sessionIngestNeedsCsrfAndGetsNoCorsHeaders() throws Exception {
        mvc.perform(post("/api/ingest").session(session("ada")).contentType(MediaType.APPLICATION_JSON).content(json("text", "hello")))
                .andExpect(status().isForbidden());
        MvcResult ok = mvc.perform(post("/api/ingest").session(session("ada")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json("text", "hello")))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(ok.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        mvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content(json("text", "hello")).with(csrf())).andExpect(status().isUnauthorized());
    }

    // ---- behaviour ----

    @Test
    void identicalUrlOrContentIsIdempotentPerUser() throws Exception {
        String t = newToken("ada");
        String bobT = newToken("bob");
        PAGES.put("https://acme.example/jobs/1", new FetchResult("u", "https://acme.example/jobs/1", 200, "text/html", ARTICLE));
        long first = id(mvc.perform(bearerPost(t, json("url", "https://acme.example/jobs/1"))).andExpect(status().isAccepted()).andReturn());
        MvcResult dup = mvc.perform(bearerPost(t, json("url", "https://ACME.example:443/jobs/1/?utm_campaign=x#frag")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.status").value("done")).andReturn();
        assertThat(id(dup)).isEqualTo(first);
        long html1 = id(mvc.perform(bearerPost(t, json("html", ARTICLE))).andReturn());
        assertThat(id(mvc.perform(bearerPost(t, json("html", ARTICLE))).andExpect(jsonPath("$.duplicate").value(true)).andReturn())).isEqualTo(html1);
        // another user's identical request is its own event and its own posting
        MvcResult other = mvc.perform(bearerPost(bobT, json("url", "https://acme.example/jobs/1"))).andExpect(jsonPath("$.duplicate").value(false)).andReturn();
        assertThat(id(other)).isNotEqualTo(first);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE owner_user_id = ?", Integer.class, ada)).isEqualTo(1);
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE owner_user_id = ?", Integer.class, bob)).isEqualTo(1);
    }

    static long id(MvcResult r) throws Exception {
        return Long.parseLong(r.getResponse().getContentAsString().replaceAll(".*\"ingestEventId\":(\\d+).*", "$1"));
    }

    @Test
    void invalidRequestsAre400And413And429() throws Exception {
        String t = newToken("ada");
        mvc.perform(bearerPost(t, "{\"kind\":\"pdf\",\"payload\":\"x\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("kind"));
        mvc.perform(bearerPost(t, "{\"payload\":\"x\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("kind"));
        mvc.perform(bearerPost(t, json("text", "  "))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("payload"));
        for (String url : new String[] {"not a url", "ftp://acme.example/", "http://localhost/x", "http://127.0.0.1/", "http://169.254.169.254/latest/meta-data", "http://10.0.0.1/", "http://[::1]/"}) {
            mvc.perform(bearerPost(t, json("url", url))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.details[0].field").value("payload"));
        }
        mvc.perform(bearerPost(t, json("text", "x".repeat(1_000_001)))).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("too_large"));
        mvc.perform(bearerPost(t, json("text", "y".repeat(1_000_000)))).andExpect(status().isAccepted());
        mvc.perform(bearerPost(t, "not json")).andExpect(status().isBadRequest());
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM ingest_event", Integer.class)).isEqualTo(1);
        // rate limit (6 per minute in this configuration; the accepted request above counted one, rejected ones do not)
        for (int i = 0; i < 5; i++) mvc.perform(bearerPost(t, json("text", "post " + i))).andExpect(status().isAccepted());
        mvc.perform(bearerPost(t, json("text", "one too many"))).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*"));
        NOW.set(NOW.get().plusSeconds(61));
        mvc.perform(bearerPost(t, json("text", "after the window"))).andExpect(status().isAccepted());
    }

    @Test
    void failuresAreRecordedOnTheEventWithACode() throws Exception {
        String t = newToken("ada");
        // text without an LLM configured here, an SSRF refusal at fetch time, and a plain fetch failure
        long text = id(mvc.perform(bearerPost(t, json("text", "Software intern at Acme"))).andReturn());
        mvc.perform(get("/api/ingest/events/" + text).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error.code").value("extraction_failed")).andExpect(jsonPath("$.jobId").isEmpty());
        FETCH_FAILURE.set(new UrlNotAllowedException("resolves to 10.0.0.1"));
        long ssrf = id(mvc.perform(bearerPost(t, json("url", "https://sneaky.example/a"))).andReturn());
        mvc.perform(get("/api/ingest/events/" + ssrf).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error.code").value("url_not_allowed")).andExpect(jsonPath("$.error.message").isString());
        FETCH_FAILURE.set(new FetchException("timeout"));
        long net = id(mvc.perform(bearerPost(t, json("url", "https://down.example/a"))).andReturn());
        mvc.perform(get("/api/ingest/events/" + net).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.error.code").value("fetch_failed"));
        FETCH_FAILURE.set(null);
        long nothing = id(mvc.perform(bearerPost(t, json("html", "<html>no structured data</html>"))).andReturn());
        mvc.perform(get("/api/ingest/events/" + nothing).header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.status").value("failed"));
        assertThat(DB.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting", Integer.class)).isZero();
    }

    @Test
    void eventsAreScopedToTheirOwnerAndLimited() throws Exception {
        String t = newToken("ada");
        String bobT = newToken("bob");
        long mine = id(mvc.perform(bearerPost(t, json("text", "mine"))).andReturn());
        for (int i = 0; i < 3; i++) mvc.perform(bearerPost(t, json("text", "more " + i)));
        mvc.perform(get("/api/ingest/events/" + mine).header("Authorization", "Bearer " + bobT)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ingest/events/" + mine).session(session("bob"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ingest/events/" + mine).session(session("ada"))).andExpect(status().isOk());
        mvc.perform(get("/api/ingest/events").header("Authorization", "Bearer " + bobT)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/ingest/events?limit=2").header("Authorization", "Bearer " + t)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.greaterThan((int) mine))); // newest first
        mvc.perform(get("/api/ingest/events/999999").header("Authorization", "Bearer " + t)).andExpect(status().isNotFound());
    }
}
