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

    @Test
    void aliasResolvesToTheCanonicalTag() throws Exception {
        Cookie s = newUser();
        long uid = userId(s);
        create(s, "PostgreSQL", "language").andExpect(status().isCreated());
        long tagId = jdbc.queryForObject("select id from tag where user_id = ? and slug = 'postgresql'", Long.class, uid);
        // aliases are managed through the alias endpoints (contract pending); seed one directly for this test
        jdbc.update("insert into tag_key (user_id, tag_id, tag_key, is_alias) values (?, ?, 'postgres', true)", uid, tagId);
        create(s, "Postgres", "language").andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("postgresql"))
                .andExpect(jsonPath("$.aliases[0]").value("postgres"));
        mvc.perform(get("/api/tags").param("q", "postgres").cookie(s)).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from tag where user_id = ?", Integer.class, uid)).isEqualTo(1);
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
