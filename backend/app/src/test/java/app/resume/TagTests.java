package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.tags.TagCategory;
import app.resume.tags.TagService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TagTests {
    @Autowired MockMvc mvc;
    @Autowired TagService tags;
    @Autowired JdbcTemplate jdbc;

    /** Signs up a fresh user and returns the session cookie. */
    Cookie newUser() throws Exception {
        String name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
    }

    long userId(Cookie session) throws Exception {
        String body = mvc.perform(get("/api/me").cookie(session)).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    org.springframework.test.web.servlet.ResultActions create(Cookie s, String name, String category) throws Exception {
        return mvc.perform(CsrfSupport.post("/api/tags").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"category\":\"" + category + "\"}"));
    }

    @Test
    void createReturns201ThenGetOrCreateReturns200WithSameTag() throws Exception {
        Cookie s = newUser();
        String first = create(s, "PostgreSQL", "language").andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("postgresql")).andExpect(jsonPath("$.name").value("PostgreSQL"))
                .andExpect(jsonPath("$.category").value("language")).andExpect(jsonPath("$.aliases").isArray())
                .andReturn().getResponse().getContentAsString();
        String second = create(s, "  postgresql ", "framework").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(second).isEqualTo(first); // same canonical tag; the second category is ignored
    }

    @Test
    void invalidRequestsAre400ValidationFailed() throws Exception {
        Cookie s = newUser();
        create(s, "!!!", "language").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        create(s, "", "language").andExpect(status().isBadRequest());
        create(s, "Java", "bogus").andExpect(status().isBadRequest());
        mvc.perform(get("/api/tags").param("category", "bogus").cookie(s)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void listFiltersByCategoryAndQueryAndIsPerUser() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        create(a, "Java", "language").andExpect(status().isCreated());
        create(a, "React", "framework").andExpect(status().isCreated());
        create(b, "Java", "language").andExpect(status().isCreated()); // same slug, other user: allowed
        mvc.perform(get("/api/tags").cookie(a)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/tags").param("category", "framework").cookie(a))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].slug").value("react"));
        mvc.perform(get("/api/tags").param("q", "JAV").cookie(a))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].slug").value("java"));
        mvc.perform(get("/api/tags").cookie(b)).andExpect(jsonPath("$.length()").value(1));
    }

    org.springframework.test.web.servlet.ResultActions addAlias(Cookie s, String slug, String alias) throws Exception {
        return mvc.perform(CsrfSupport.post("/api/tags/" + slug + "/aliases").cookie(s)
                .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias + "\"}"));
    }

    org.springframework.test.web.servlet.ResultActions removeAlias(Cookie s, String slug, String alias) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/tags/" + slug + "/aliases/" + alias).cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN))
                .header("X-XSRF-TOKEN", CsrfSupport.TOKEN).cookie(s));
    }

    @Test
    void addAliasNormalizesAndLaterCreateResolvesToTheCanonicalTag() throws Exception {
        Cookie s = newUser();
        create(s, "PostgreSQL", "language").andExpect(status().isCreated());
        addAlias(s, "postgresql", "  Post GRES! ").andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("postgresql")).andExpect(jsonPath("$.aliases[0]").value("post-gres"));
        create(s, "post gres", "framework").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("postgresql"));
        mvc.perform(get("/api/tags").param("q", "post-gres").cookie(s)).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from tag where user_id = ?", Integer.class, userId(s))).isEqualTo(1);
    }

    @Test
    void addingAnExistingAliasOrTheOwnSlugIsANoOp200() throws Exception {
        Cookie s = newUser();
        create(s, "Kubernetes", "domain").andExpect(status().isCreated());
        addAlias(s, "kubernetes", "k8s").andExpect(status().isOk());
        addAlias(s, "kubernetes", "K8S").andExpect(status().isOk()).andExpect(jsonPath("$.aliases.length()").value(1));
        addAlias(s, "kubernetes", "Kubernetes").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("kubernetes"))
                .andExpect(jsonPath("$.aliases.length()").value(1));
    }

    @Test
    void aliasConflictsWithAnotherTagsSlugOrAliasReturn409WithTheConflictingSlug() throws Exception {
        Cookie s = newUser();
        create(s, "MySQL", "language").andExpect(status().isCreated());
        create(s, "PostgreSQL", "language").andExpect(status().isCreated());
        addAlias(s, "mysql", "sql-db").andExpect(status().isOk());
        addAlias(s, "postgresql", "mysql").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("alias_conflict"))
                .andExpect(jsonPath("$.conflictingSlug").value("mysql")).andExpect(jsonPath("$.message").isString());
        addAlias(s, "postgresql", "SQL DB").andExpect(status().isConflict()).andExpect(jsonPath("$.conflictingSlug").value("mysql"));
        mvc.perform(get("/api/tags").param("q", "postgresql").cookie(s)).andExpect(jsonPath("$[0].aliases.length()").value(0));
    }

    @Test
    void conflictsAreScopedToTheUser() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        create(a, "Rust", "language").andExpect(status().isCreated());
        create(b, "Go", "language").andExpect(status().isCreated());
        create(b, "Golang", "language").andExpect(status().isCreated());
        addAlias(a, "rust", "golang").andExpect(status().isOk()); // user B's "golang" is invisible to user A
        addAlias(b, "go", "rust").andExpect(status().isOk());     // and user A's "rust" to B
        addAlias(b, "go", "golang").andExpect(status().isConflict()).andExpect(jsonPath("$.conflictingSlug").value("golang"));
        addAlias(a, "go", "x").andExpect(status().isNotFound());  // another user's tag is not found
    }

    @Test
    void badAliasRequestsAre400() throws Exception {
        Cookie s = newUser();
        create(s, "Python", "language").andExpect(status().isCreated());
        addAlias(s, "python", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        addAlias(s, "python", "!!!").andExpect(status().isBadRequest());
        addAlias(s, "python", "a".repeat(61)).andExpect(status().isBadRequest());
        addAlias(s, "python", "a".repeat(60)).andExpect(status().isOk()); // limit counts code points of the raw input
        mvc.perform(CsrfSupport.post("/api/tags/python/aliases").cookie(s).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sixtyCodePointsIsTheLimitEvenForSurrogatePairs() throws Exception {
        Cookie s = newUser();
        create(s, "Emoji", "domain").andExpect(status().isCreated());
        String sixty = "\uD83D\uDE00".repeat(59) + "a"; // 60 code points, 119 chars
        addAlias(s, "emoji", sixty).andExpect(status().isOk());
        addAlias(s, "emoji", "\uD83D\uDE00".repeat(60) + "b").andExpect(status().isBadRequest()); // 61
    }

    @Test
    void removeAliasReturnsTheUpdatedTagAndUnknownThingsAre404() throws Exception {
        Cookie s = newUser();
        create(s, "Java", "language").andExpect(status().isCreated());
        addAlias(s, "java", "jdk").andExpect(status().isOk());
        removeAlias(s, "java", "jdk").andExpect(status().isOk()).andExpect(jsonPath("$.aliases.length()").value(0))
                .andExpect(jsonPath("$.slug").value("java"));
        removeAlias(s, "java", "jdk").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        removeAlias(s, "java", "java").andExpect(status().isNotFound()); // the slug is not an alias
        removeAlias(s, "nope", "jdk").andExpect(status().isNotFound());
        removeAlias(s, "Java", "jdk").andExpect(status().isNotFound());  // not a slug
        removeAlias(s, "java", "JDK").andExpect(status().isNotFound());  // not a normalized alias
        addAlias(s, "nope", "x").andExpect(status().isNotFound());
        addAlias(s, "Not-A-Slug", "x").andExpect(status().isNotFound());
        create(s, "Scala", "language").andExpect(status().isCreated());
        addAlias(s, "scala", "jdk").andExpect(status().isOk()); // freed alias can be reused by another tag
    }

    @Test
    void aliasChangesNeverTouchNodeTagsAndHaveNoCap() throws Exception {
        Cookie s = newUser();
        create(s, "Docker", "framework").andExpect(status().isCreated());
        for (int i = 0; i < 25; i++) addAlias(s, "docker", "alias" + i).andExpect(status().isOk());
        mvc.perform(get("/api/tags").param("q", "docker").cookie(s)).andExpect(jsonPath("$[0].aliases.length()").value(25));
        // node tags reference slugs only; there is no resume_node_tag table yet (US-021), so nothing to change here
        assertThat(jdbc.queryForObject("select count(*) from tag_key where is_alias = true and user_id = ?", Integer.class, userId(s)))
                .isEqualTo(25);
    }

    @Test
    void aliasEndpointsRequireSignInAndCsrf() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tags/x/aliases")
                        .cookie(new Cookie("XSRF-TOKEN", "t")).header("X-XSRF-TOKEN", "t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"a\"}"))
                .andExpect(status().isUnauthorized());
        Cookie s = newUser();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tags/x/aliases").cookie(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"a\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tags/x/aliases/a").cookie(s))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void tagEndpointsRequireSignInAndCsrf() throws Exception {
        mvc.perform(get("/api/tags")).andExpect(status().isUnauthorized());
        Cookie s = newUser();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tags").cookie(s)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"category\":\"domain\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
    }

    @Test
    void serviceRejectsNamesWithoutLettersOrDigits() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tags.getOrCreate(1, "...", TagCategory.DOMAIN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
