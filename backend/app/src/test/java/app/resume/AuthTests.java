package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.auth.AuthController;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(creds(user, pass)))
                .andReturn().getResponse();
    }

    MockHttpServletResponse logIn(String user, String pass) throws Exception {
        return mvc.perform(CsrfSupport.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(creds(user, pass)))
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
        assertThat(signUp("a".repeat(41), "correct-horse-battery").getStatus()).isEqualTo(400);
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
        MockHttpServletResponse res = mvc.perform(CsrfSupport.post("/api/auth/login").cookie(before)
                .contentType(MediaType.APPLICATION_JSON).content(creds("fixation", "correct-horse-battery")))
                .andReturn().getResponse();
        assertThat(session(res).getValue()).isNotEqualTo(before.getValue());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Cookie cookie = session(signUp("barbara", "correct-horse-battery"));
        mvc.perform(CsrfSupport.post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void meAndLogoutReturn401WhenSignedOut() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.message").isString());
        mvc.perform(CsrfSupport.post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiNeedsASessionButHealthDoesNot() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
    }

    @Test
    void passwordsAreTruncatedToTheirFirst72BytesAtSignUpAndLogin() throws Exception {
        String base = "p".repeat(72);
        assertThat(signUp("longpass", base + "-this-part-is-ignored").getStatus()).isEqualTo(201);
        // only the first 72 bytes count, so the 72-byte prefix and any other tail both log in
        assertThat(logIn("longpass", base).getStatus()).isEqualTo(200);
        assertThat(logIn("longpass", base + "a-different-tail").getStatus()).isEqualTo(200);
        assertThat(logIn("longpass", "q" + base.substring(1)).getStatus()).isEqualTo(401);
        // very long passwords are not an error
        assertThat(signUp("hugepass", "x".repeat(5000)).getStatus()).isEqualTo(201);
    }

    @Test
    void truncationNeverCutsAMultiByteCharacterInHalf() {
        String euro = "\u20ac"; // 3 bytes in UTF-8; 24 of them are exactly 72 bytes
        String exact = euro.repeat(24);
        assertThat(AuthController.usablePassword(exact)).isEqualTo(exact);
        // 71 ASCII bytes + one 3-byte char would straddle byte 72: the whole character is dropped
        String straddle = "a".repeat(71) + euro + "tail";
        assertThat(AuthController.usablePassword(straddle)).isEqualTo("a".repeat(71));
        assertThat(AuthController.usablePassword("short")).isEqualTo("short");
    }

    @Test
    void usernamePatternIsEnforced() throws Exception {
        for (String bad : new String[] {"ab", "has space", "semi;colon", "caf\u00e9"}) {
            assertThat(signUp(bad, "correct-horse-battery").getStatus()).as(bad).isEqualTo(400);
        }
        assertThat(signUp("ok_name-1.x", "correct-horse-battery").getStatus()).isEqualTo(201);
    }

    @Test
    void stateChangingRequestsWithoutCsrfTokenGet403CsrfInvalid() throws Exception {
        for (String url : new String[] {"/api/auth/signup", "/api/auth/login", "/api/auth/logout"}) {
            mvc.perform(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON)
                            .content(creds("nocsrf", "correct-horse-battery")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("csrf_invalid"))
                    .andExpect(jsonPath("$.message").isString());
        }
        // wrong token: cookie and header differ
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").cookie(new Cookie("XSRF-TOKEN", "a"))
                        .header("X-XSRF-TOKEN", "b").contentType(MediaType.APPLICATION_JSON)
                        .content(creds("nocsrf", "correct-horse-battery")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        assertThat(jdbc.queryForObject("select count(*) from app_user where username = 'nocsrf'", Integer.class))
                .isZero();
    }

    @Test
    void meSetsReadableXsrfCookieOnBothSuccessAndUnauthorized() throws Exception {
        Cookie signedOut = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(signedOut).isNotNull();
        assertThat(signedOut.isHttpOnly()).isFalse();
        assertThat(signedOut.getValue()).isNotBlank();
        // the signed-out client can now sign up using the token it was given
        var res = mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup").cookie(signedOut)
                        .header("X-XSRF-TOKEN", signedOut.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content(creds("withtoken", "correct-horse-battery")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        Cookie signedIn = mvc.perform(get("/api/me").cookie(session(res)).cookie(signedOut))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(signedIn).isNotNull();
    }

    @Test
    void signUpRejectsNonJsonContentType() throws Exception {
        MockHttpServletRequestBuilder form = CsrfSupport.post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username", "ada").param("password", "x");
        mvc.perform(form).andExpect(status().isUnsupportedMediaType());
    }
}
