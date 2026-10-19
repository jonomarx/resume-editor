package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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

    /** Name of the hashed JS file the real Vite build references, e.g. /assets/index-CdHJH-nP.js. */
    static String assetPath;

    /**
     * These tests serve the real frontend build (frontend/dist, copied onto the test classpath by the app pom).
     * Without a build they are skipped locally; CI builds the frontend first and sets REQUIRE_FRONTEND_BUILD=true,
     * which turns a missing build into a failure.
     */
    @BeforeAll
    static void requireFrontendBuild() throws Exception {
        ClassPathResource index = new ClassPathResource("static/index.html");
        if (!index.exists()) {
            if ("true".equals(System.getenv("REQUIRE_FRONTEND_BUILD"))) {
                throw new AssertionError("frontend/dist is missing: run `pnpm build` in frontend/ first");
            }
            Assumptions.abort("frontend/dist not built; run `pnpm build` in frontend/ to enable SpaServingTests");
        }
        String html = new String(index.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("/assets/[^\"']+\\.js").matcher(html);
        assertThat(m.find()).as("index.html references a hashed /assets/*.js file").isTrue();
        assetPath = m.group();
    }

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
        mvc.perform(get(assetPath))
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
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized()); // signed out: JSON 401, never index.html
        assertThat(mvc.perform(get("/api/does-not-exist")).andReturn().getResponse().getContentAsString())
                .doesNotContain("<div id=\"root\">");
    }

    @Test
    void missingFilesAre404NotIndexHtml() throws Exception {
        mvc.perform(get("/assets/missing-1234.js")).andExpect(status().isNotFound());
        mvc.perform(get("/nothing.png")).andExpect(status().isNotFound());
    }
}
