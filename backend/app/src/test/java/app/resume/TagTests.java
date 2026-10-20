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
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("alias", alias));
        return mvc.perform(CsrfSupport.post("/api/tags/" + slug + "/aliases").cookie(s)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** DELETE with the alias passed percent-encoded in the query, like a real client. */
    org.springframework.test.web.servlet.ResultActions removeAlias(Cookie s, String slug, String alias) throws Exception {
        String encoded = java.net.URLEncoder.encode(alias, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                java.net.URI.create("/api/tags/" + slug + "/aliases?alias=" + encoded)).cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN))
                .header("X-XSRF-TOKEN", CsrfSupport.TOKEN).cookie(s));
    }

    @Test
    void cCPlusPlusAndCSharpAreThreeTagsAndNameResolutionUsesAliasKeysFirst() throws Exception {
        Cookie s = newUser();
        create(s, "C", "language").andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("c"));
        create(s, "C++", "language").andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("c-plus-plus"));
        create(s, "C#", "language").andExpect(status().isCreated()).andExpect(jsonPath("$.slug").value("c-sharp"));
        create(s, "c++", "language").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("c-plus-plus"));
        // an alias key is checked on the raw name before the slug is computed
        addAlias(s, "c-sharp", "C Sharp").andExpect(status().isOk()).andExpect(jsonPath("$.aliases[0]").value("C Sharp"));
        create(s, "  c sharp ", "language").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("c-sharp"));
        mvc.perform(get("/api/tags").cookie(s)).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void aSlugThatEqualsAnAliasKeyResolvesToTheAliasOwnerInsteadOfCreatingAClash() throws Exception {
        Cookie s = newUser();
        create(s, "Kubernetes", "domain").andExpect(status().isCreated());
        addAlias(s, "kubernetes", "K8S").andExpect(status().isOk());
        create(s, "k8s!", "domain").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("kubernetes")); // slug k8s = alias key k8s
        mvc.perform(get("/api/tags").cookie(s)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aliasesKeepTheirTypedFormAndMayContainPlusHashDotSlashAndSpaces() throws Exception {
        Cookie s = newUser();
        create(s, "C++", "language").andExpect(status().isCreated());
        for (String alias : new String[] {"C++", ".NET core/3", "c#-ish", " 50% done ", "~!@$^&*()"}) {
            addAlias(s, "c-plus-plus", alias).andExpect(status().isOk());
        }
        mvc.perform(get("/api/tags").param("q", "c-plus").cookie(s)).andExpect(jsonPath("$[0].aliases.length()").value(5))
                .andExpect(jsonPath("$[0].aliases", org.hamcrest.Matchers.hasItems("C++", "50% done", ".NET core/3"))); // trimmed, case kept
        create(s, ".net CORE/3", "language").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("c-plus-plus"));
    }

    @Test
    void addingAnExistingAliasInAnyCaseOrTheOwnSlugIsANoOp200() throws Exception {
        Cookie s = newUser();
        create(s, "Kubernetes", "domain").andExpect(status().isCreated());
        addAlias(s, "kubernetes", "k8s").andExpect(status().isOk());
        addAlias(s, "kubernetes", "K8S").andExpect(status().isOk()).andExpect(jsonPath("$.aliases.length()").value(1))
                .andExpect(jsonPath("$.aliases[0]").value("k8s")); // the first spelling stays
        addAlias(s, "kubernetes", "Kubernetes").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("kubernetes"))
                .andExpect(jsonPath("$.aliases.length()").value(1));
    }

    @Test
    void conflictsAreCaseInsensitiveAndCoverOtherTagsSlugsAndAliases() throws Exception {
        Cookie s = newUser();
        create(s, "MySQL", "language").andExpect(status().isCreated());
        create(s, "PostgreSQL", "language").andExpect(status().isCreated());
        addAlias(s, "mysql", "SQL DB").andExpect(status().isOk());
        addAlias(s, "postgresql", "MYSQL").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("alias_conflict"))
                .andExpect(jsonPath("$.conflictingSlug").value("mysql")).andExpect(jsonPath("$.message").isString());
        addAlias(s, "postgresql", "sql db").andExpect(status().isConflict()).andExpect(jsonPath("$.conflictingSlug").value("mysql"));
        mvc.perform(get("/api/tags").param("q", "postgresql").cookie(s)).andExpect(jsonPath("$[0].aliases.length()").value(0));
        // the owner's example: alias "C" on c-plus-plus equals the slug of tag c
        create(s, "C", "language").andExpect(status().isCreated());
        create(s, "C++", "language").andExpect(status().isCreated());
        addAlias(s, "c-plus-plus", "C").andExpect(status().isConflict()).andExpect(jsonPath("$.conflictingSlug").value("c"));
    }

    @Test
    void conflictsAreScopedToTheUser() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        create(a, "Rust", "language").andExpect(status().isCreated());
        create(b, "Go", "language").andExpect(status().isCreated());
        create(b, "Golang", "language").andExpect(status().isCreated());
        addAlias(a, "rust", "golang").andExpect(status().isOk()); // user B's "golang" is invisible to user A
        addAlias(b, "go", "RUST").andExpect(status().isOk());     // and user A's "rust" to B
        addAlias(b, "go", "Golang").andExpect(status().isConflict()).andExpect(jsonPath("$.conflictingSlug").value("golang"));
        addAlias(a, "go", "x").andExpect(status().isNotFound());  // another user's tag is not found
    }

    @Test
    void badAliasesAre400IncludingNonAsciiAndControlCharacters() throws Exception {
        Cookie s = newUser();
        create(s, "Python", "language").andExpect(status().isCreated());
        for (String bad : new String[] {"café", "", "   ", "tab\there", "line\nbreak", "\u007F", "emoji \uD83D\uDE00", "x".repeat(61), " " + "y".repeat(61)}) {
            addAlias(s, "python", bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.details[0].field").value("alias"));
        }
        addAlias(s, "python", "z".repeat(60)).andExpect(status().isOk());
        addAlias(s, "python", "  " + "w".repeat(60) + "  ").andExpect(status().isOk()); // the 60 limit is after trimming
        mvc.perform(CsrfSupport.post("/api/tags/python/aliases").cookie(s).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteTakesAPercentEncodedAliasMatchedByAliasKey() throws Exception {
        Cookie s = newUser();
        create(s, "C++", "language").andExpect(status().isCreated());
        addAlias(s, "c-plus-plus", "C++").andExpect(status().isOk());
        addAlias(s, "c-plus-plus", ".NET / Core").andExpect(status().isOk());
        removeAlias(s, "c-plus-plus", "c++").andExpect(status().isOk()).andExpect(jsonPath("$.aliases.length()").value(1))
                .andExpect(jsonPath("$.slug").value("c-plus-plus"));
        removeAlias(s, "c-plus-plus", ".net / core").andExpect(status().isOk()).andExpect(jsonPath("$.aliases.length()").value(0));
        removeAlias(s, "c-plus-plus", "C++").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        removeAlias(s, "c-plus-plus", "c-plus-plus").andExpect(status().isNotFound()); // the slug is not an alias
        removeAlias(s, "c-plus-plus", "café").andExpect(status().isNotFound());       // non-ASCII: nothing to match
        removeAlias(s, "c-plus-plus", "x".repeat(70)).andExpect(status().isNotFound());
        removeAlias(s, "nope", "C++").andExpect(status().isNotFound());
        removeAlias(s, "Not-A-Slug", "C++").andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tags/c-plus-plus/aliases")
                        .cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN)).header("X-XSRF-TOKEN", CsrfSupport.TOKEN).cookie(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("alias"));
        // the old path-segment form is gone
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tags/c-plus-plus/aliases/cpp")
                        .cookie(new Cookie("XSRF-TOKEN", CsrfSupport.TOKEN)).header("X-XSRF-TOKEN", CsrfSupport.TOKEN).cookie(s))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aFreedAliasCanBeUsedByAnotherTagAndNeverChangesNodeTags() throws Exception {
        Cookie s = newUser();
        create(s, "Java", "language").andExpect(status().isCreated());
        create(s, "Scala", "language").andExpect(status().isCreated());
        addAlias(s, "java", "JDK").andExpect(status().isOk());
        removeAlias(s, "java", "jdk").andExpect(status().isOk());
        addAlias(s, "scala", "jdk").andExpect(status().isOk());
        for (int i = 0; i < 25; i++) addAlias(s, "scala", "alias " + i).andExpect(status().isOk()); // no cap
        mvc.perform(get("/api/tags").param("q", "scala").cookie(s)).andExpect(jsonPath("$[0].aliases.length()").value(26));
        // node tags reference slugs only (US-021): alias changes touch only tag_key rows, never a tag row
        assertThat(jdbc.queryForObject("select count(*) from tag where user_id = ?", Integer.class, userId(s))).isEqualTo(2);
    }

    @Test
    void qMatchesNameSlugAndAliasKeyCaseInsensitivelyWithWildcardsTakenLiterally() throws Exception {
        Cookie s = newUser();
        create(s, "Node.js", "framework").andExpect(status().isCreated());
        create(s, "Discount", "domain").andExpect(status().isCreated());
        addAlias(s, "discount", "50% OFF").andExpect(status().isOk());
        addAlias(s, "node-js", "Server_Side JS").andExpect(status().isOk());
        mvc.perform(get("/api/tags").param("q", "NODE").cookie(s)).andExpect(jsonPath("$.length()").value(1)); // name and slug
        mvc.perform(get("/api/tags").param("q", "node-j").cookie(s)).andExpect(jsonPath("$.length()").value(1)); // slug
        mvc.perform(get("/api/tags").param("q", "server_side").cookie(s)).andExpect(jsonPath("$[0].slug").value("node-js")); // alias key
        mvc.perform(get("/api/tags").param("q", "50% o").cookie(s)).andExpect(jsonPath("$[0].slug").value("discount"));
        mvc.perform(get("/api/tags").param("q", "%").cookie(s)).andExpect(jsonPath("$.length()").value(1)); // not "match everything"
        mvc.perform(get("/api/tags").param("q", "_").cookie(s)).andExpect(jsonPath("$.length()").value(1)); // only Server_Side
        mvc.perform(get("/api/tags").param("q", "d_sc").cookie(s)).andExpect(jsonPath("$.length()").value(0)); // _ is not a wildcard
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
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tags/x/aliases?alias=a").cookie(s))
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
