package app.resume.jobs.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SourceApiTests {
    JobsTestDb db;
    long ada;
    long bob;
    long current;
    MockMvc mvc;

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
        mvc = MockMvcBuilders.standaloneSetup(new SourceController(new SourceService(db.jdbc, new PublicHostPolicy()), users))
                .setControllerAdvice(new JobsApiAdvice()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    String create(String url, String name) throws Exception {
        String body = name == null ? "{\"url\":\"" + url + "\"}" : "{\"url\":\"" + url + "\",\"name\":\"" + name + "\"}";
        return mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void createNormalizesDefaultsNameAndListsOldestFirst() throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"HTTPS://Acme.Example:443/careers/?utm_source=x&b=2&a=1#top\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://acme.example/careers?a=1&b=2"))
                .andExpect(jsonPath("$.name").value("acme.example"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.extractionTier").isEmpty())
                .andExpect(jsonPath("$.lastCrawlAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").isString());
        create("https://beta.example/jobs", "Beta");
        mvc.perform(get("/api/sources")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("acme.example")).andExpect(jsonPath("$[1].name").value("Beta"));
    }

    @Test
    void duplicateNormalizedUrlIs409WithExistingId() throws Exception {
        String first = create("https://acme.example/careers", null);
        long id = Long.parseLong(first.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://ACME.example:443/careers/?utm_campaign=z\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("source_exists"))
                .andExpect(jsonPath("$.existingSourceId").value(id));
        current = bob; // another user may add the same page
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://acme.example/careers\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidAndNonPublicUrlsAre400ValidationFailedOnUrl() throws Exception {
        for (String url : new String[] {"", "acme.example", "ftp://acme.example/", "http://localhost/jobs", "http://127.0.0.1:8080/",
                "http://169.254.169.254/latest/meta-data", "http://10.0.0.5/", "http://[::1]/", "https://x.example/" + "a".repeat(2000)}) {
            mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.details[0].field").value("url"));
        }
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("url"));
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://a.example/\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("name"));
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_source", Integer.class)).isZero();
    }

    @Test
    void getUpdateAndDelete() throws Exception {
        long id = Long.parseLong(create("https://acme.example/careers", "Acme").replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(get("/api/sources/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme"));
        mvc.perform(put("/api/sources/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.name").value("Acme"));
        mvc.perform(put("/api/sources/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Acme (summer)\",\"url\":\"https://evil.example/\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme (summer)"))
                .andExpect(jsonPath("$.url").value("https://acme.example/careers")); // the URL cannot be changed
        mvc.perform(put("/api/sources/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());

        // A posting from this source survives deletion with its source cleared; crawl history goes.
        db.jdbc.update("INSERT INTO job_posting (owner_user_id, source_id, origin, canonical_url, url_hash, content_hash, dedupe_key, title, company)"
                + " VALUES (?, ?, 'crawl', 'https://acme.example/j/1', 'h', 'c', 'd', 'Intern', 'Acme')", ada, id);
        db.jdbc.update("INSERT INTO crawl_run (user_id, source_id) VALUES (?, ?)", ada, id);
        mvc.perform(delete("/api/sources/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sources/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM job_posting WHERE source_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM crawl_run", Integer.class)).isZero();
        mvc.perform(delete("/api/sources/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotReadChangeOrDeleteASource() throws Exception {
        long id = Long.parseLong(create("https://acme.example/careers", "Acme").replaceAll(".*\"id\":(\\d+).*", "$1"));
        current = bob;
        mvc.perform(get("/api/sources/" + id)).andExpect(status().isNotFound());
        mvc.perform(put("/api/sources/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine\",\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sources/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sources")).andExpect(jsonPath("$.length()").value(0));
        current = ada;
        mvc.perform(get("/api/sources/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Acme"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void signedOutIs401() throws Exception {
        current = 0;
        mvc.perform(get("/api/sources")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://a.example/\"}"))
                .andExpect(status().isUnauthorized());
    }
}
