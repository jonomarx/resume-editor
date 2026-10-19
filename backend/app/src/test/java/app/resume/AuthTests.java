package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static String creds(String user, String pass) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
    }

    MockHttpServletResponse signUp(String user, String pass) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(creds(user, pass)))
                .andReturn().getResponse();
    }

    MockHttpServletResponse logIn(String user, String pass) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(creds(user, pass)))
                .andReturn().getResponse();
    }

    static Cookie session(MockHttpServletResponse res) {
        Cookie c = res.getCookie("SESSION");
        assertThat(c).as("SESSION cookie").isNotNull();
        return c;
    }

    @Test
    void signUpCreatesAccountStartsSessionAndHashesPassword() throws Exception {
        MockHttpServletResponse res = signUp("ada", "correct-horse-battery");
        assertThat(res.getStatus()).isEqualTo(201);
        assertThat(res.getContentAsString()).contains("\"username\":\"ada\"").contains("\"id\":");
        Cookie cookie = session(res);
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("ada"));
        String hash = jdbc.queryForObject("select password_hash from app_user where username = 'ada'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse-battery");
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSiteLax() throws Exception {
        MockHttpServletResponse res = signUp("cookieuser", "correct-horse-battery");
        List<String> setCookies = res.getHeaders("Set-Cookie");
        String header = setCookies.stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(header).contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void duplicateUsernameReturnsContractConflict_caseInsensitive() throws Exception {
        assertThat(signUp("grace", "correct-horse-battery").getStatus()).isEqualTo(201);
        MockHttpServletResponse res = signUp("GRACE", "another-password");
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("\"code\":\"username_taken\"").contains("\"message\":");
    }

    @Test
    void invalidSignUpReturnsValidationErrorWithDetails() throws Exception {
        MockHttpServletResponse res = signUp("ab", "short");
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("validation_failed").contains("username").contains("password");
        assertThat(signUp("has space", "correct-horse-battery").getStatus()).isEqualTo(400);
        assertThat(signUp("longpw", "x".repeat(73)).getStatus()).isEqualTo(400);
    }

    @Test
    void loginSucceedsAndStartsNewSession() throws Exception {
        signUp("linus", "correct-horse-battery");
        MockHttpServletResponse res = logIn("LINUS", "correct-horse-battery");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).contains("\"username\":\"linus\"");
        mvc.perform(get("/api/me").cookie(session(res))).andExpect(status().isOk());
    }

    @Test
    void badPasswordAndUnknownUserGetTheSameContractError() throws Exception {
        signUp("margaret", "correct-horse-battery");
        for (MockHttpServletResponse res : new MockHttpServletResponse[] {
                logIn("margaret", "wrong-password"), logIn("nobody-here", "wrong-password")}) {
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("\"code\":\"bad_credentials\"");
            assertThat(res.getCookie("SESSION")).isNull();
        }
    }

    @Test
    void loginDoesNotReuseAPreLoginSessionId() throws Exception {
        signUp("fixation", "correct-horse-battery");
        Cookie before = session(logIn("fixation", "correct-horse-battery"));
        MockHttpServletResponse res = mvc.perform(post("/api/auth/login").cookie(before)
                .contentType(MediaType.APPLICATION_JSON).content(creds("fixation", "correct-horse-battery")))
                .andReturn().getResponse();
        assertThat(session(res).getValue()).isNotEqualTo(before.getValue());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie cookie = session(signUp("barbara", "correct-horse-battery"));
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void meAndLogoutReturn401WhenSignedOut() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiNeedsASessionButHealthDoesNot() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void signUpRejectsNonJsonContentType() throws Exception {
        MockHttpServletRequestBuilder form = post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username", "ada").param("password", "x");
        mvc.perform(form).andExpect(status().isUnsupportedMediaType());
    }
}
