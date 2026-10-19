package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpaServingTests {
    @Autowired
    MockMvc mvc;

    @Test
    void rootAndClientRoutesServeIndexHtml() throws Exception {
        for (String path : new String[] {"/login", "/drafts/42", "/master/edit"}) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
        }
    }

    @Test
    void rootForwardsToIndexHtml() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
    }

    @Test
    void indexHtmlIsNeverCached() throws Exception {
        String cc = mvc.perform(get("/drafts/42")).andReturn().getResponse().getHeader("Cache-Control");
        assertThat(cc).contains("no-cache");
        assertThat(cc).doesNotContain("max-age=31536000");
        assertThat(mvc.perform(get("/index.html")).andReturn().getResponse().getHeader("Cache-Control"))
                .contains("no-cache");
    }

    @Test
    void hashedAssetsAreCachedLongAndImmutable() throws Exception {
        mvc.perform(get("/assets/app-abc123.js"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("max-age=31536000"),
                        org.hamcrest.Matchers.containsString("immutable"))));
    }

    @Test
    void apiPathsAreNotForwarded() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
        assertThat(mvc.perform(get("/api/does-not-exist")).andReturn().getResponse().getContentAsString())
                .doesNotContain("<div id=\"root\">");
    }

    @Test
    void missingFilesAre404NotIndexHtml() throws Exception {
        mvc.perform(get("/assets/missing-1234.js")).andExpect(status().isNotFound());
        mvc.perform(get("/nothing.png")).andExpect(status().isNotFound());
    }
}
