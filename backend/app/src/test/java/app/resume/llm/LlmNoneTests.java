package app.resume.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import app.resume.jobs.ingest.IngestProcessor;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** US-110: LLM_PROVIDER=none, the mode the app runs in by default. */
@SpringBootTest(properties = "app.llm.provider=none")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LlmNoneTests {
    static final LlmRequest REQ = new LlmRequest("Extract.", "<posting>Intern at Acme</posting>", 200);

    @Autowired LlmClient client;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestProcessor ingest;

    @Test
    void everyCallThrowsLlmUnavailable() {
        assertThatThrownBy(() -> client.complete(REQ)).isInstanceOfSatisfying(LlmUnavailableException.class,
                e -> assertThat(e.code()).isEqualTo("llm_unavailable"));
        assertThatThrownBy(() -> client.completeJson(REQ, "{\"type\":\"object\"}"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void noneIsSelectedByNameAndIsTheApplicationDefault() throws Exception {
        assertThat(new LlmConfig().llmProvider("none")).isInstanceOf(NoLlmProvider.class);
        assertThat(new LlmConfig().llmProvider("none").available()).isFalse();
        // the shipped default (tests override it with fake): application.yml names none
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("provider: ${LLM_PROVIDER:none}");
    }

    @Test
    void capabilitiesReportsNoLlmAndNeedsASession() throws Exception {
        mvc.perform(get("/api/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/capabilities").cookie(signUp())).andExpect(status().isOk())
                .andExpect(jsonPath("$.llmAvailable").value(false));
    }

    @Test
    void freeTextIngestRecordsLlmUnavailableOnTheEvent() throws Exception {
        Cookie s = signUp();
        String me = mvc.perform(get("/api/me").cookie(s)).andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(me.replaceAll(".*\"id\":(\\d+).*", "$1"));
        jdbc.update("insert into ingest_event (user_id, kind, idempotency_key, status, payload) values (?, 'text', ?, 'queued', ?)",
                userId, UUID.randomUUID().toString().replace("-", ""), "Software intern at Acme");
        long id = jdbc.queryForObject("select id from ingest_event where user_id = ?", Long.class, userId);
        assertThat(ingest.process(id)).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select status, error_code from ingest_event where id = ?", id);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("error_code")).isEqualTo("llm_unavailable");
    }

    Cookie signUp() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
    }
}

