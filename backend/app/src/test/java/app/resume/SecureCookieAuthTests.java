package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(properties = "SESSION_COOKIE_SECURE=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecureCookieAuthTests {
    @Autowired MockMvc mvc;

    static String creds(String user, String pass) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
    }

    @Test
    void cookiesHaveSecureFlagWhenSessionCookieSecureIsTrue() throws Exception {
        MockHttpServletResponse getMeRes = mvc.perform(get("/api/me")).andReturn().getResponse();
        List<String> getMeCookies = getMeRes.getHeaders("Set-Cookie");
        String xsrfHeader = getMeCookies.stream().filter(h -> h.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(xsrfHeader).contains("Secure").contains("SameSite=Lax").doesNotContain("HttpOnly");

        Cookie xsrfCookie = getMeRes.getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.isHttpOnly()).isFalse();

        MockHttpServletResponse signUpRes = mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup")
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creds("secureuser", "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        List<String> signUpCookies = signUpRes.getHeaders("Set-Cookie");
        String sessionHeader = signUpCookies.stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(sessionHeader).contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void csrfFlowWorksWhenSessionCookieSecureIsTrue() throws Exception {
        // 1. Initial unauthenticated request gets XSRF-TOKEN
        MockHttpServletResponse meRes = mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andReturn().getResponse();
        Cookie xsrf = meRes.getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();

        // 2. State-changing request without CSRF token is rejected with 403 csrf_invalid
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creds("attacker", "correct-horse-battery")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));

        // 3. State-changing request with mismatched CSRF token is rejected with 403 csrf_invalid
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup")
                        .cookie(new Cookie("XSRF-TOKEN", "good-cookie"))
                        .header("X-XSRF-TOKEN", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creds("attacker", "correct-horse-battery")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("csrf_invalid"));

        // 4. Valid CSRF token allows signing up
        MockHttpServletResponse signupRes = mvc.perform(MockMvcRequestBuilders.post("/api/auth/signup")
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creds("flowuser", "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        Cookie session = signupRes.getCookie("SESSION");
        assertThat(session).isNotNull();

        // 5. Subsequent authenticated request with SESSION and XSRF-TOKEN succeeds
        MockHttpServletResponse authMeRes = mvc.perform(get("/api/me").cookie(session).cookie(xsrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("flowuser"))
                .andReturn().getResponse();

        Cookie updatedXsrf = authMeRes.getCookie("XSRF-TOKEN");
        Cookie activeXsrf = updatedXsrf != null ? updatedXsrf : xsrf;

        // 6. Logout with valid CSRF token succeeds
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/logout")
                        .cookie(session)
                        .cookie(activeXsrf)
                        .header("X-XSRF-TOKEN", activeXsrf.getValue()))
                .andExpect(status().isNoContent());

        // 7. Verify session is invalidated
        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
    }
}
