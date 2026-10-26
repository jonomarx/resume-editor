package app.resume.llm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** With a working provider (the fake) capabilities report the LLM as available. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LlmFakeCapabilitiesTests {
    @Autowired MockMvc mvc;

    @Test
    void llmIsAvailableWithTheFake() throws Exception {
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"capsuser\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        mvc.perform(get("/api/capabilities").cookie(s)).andExpect(status().isOk()).andExpect(jsonPath("$.llmAvailable").value(true));
    }
}
