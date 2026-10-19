package app.resume.render;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.render.max-bytes=2000")
class RenderTooLargeTests {
    @Autowired MockMvc mvc;
    @Autowired StarterTree starter;
    @Autowired ObjectMapper mapper;

    @Test
    void aDocumentOverTheSizeLimitIs413WithoutCallingTheRenderer() throws Exception {
        var s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"bigdoc\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        int before = FakeRendererConfig.CALLS.get();
        mvc.perform(CsrfSupport.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("tree", starter.create()))))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("too_large"));
        org.assertj.core.api.Assertions.assertThat(FakeRendererConfig.CALLS.get()).isEqualTo(before);
    }
}
