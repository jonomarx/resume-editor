package app.resume;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Test helper: a POST that carries a matching XSRF-TOKEN cookie and X-XSRF-TOKEN header, like the SPA does. */
public final class CsrfSupport {
    public static final String TOKEN = "test-xsrf-token";

    private CsrfSupport() {}

    public static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).cookie(new Cookie("XSRF-TOKEN", TOKEN)).header("X-XSRF-TOKEN", TOKEN);
    }

    public static MockHttpServletRequestBuilder put(String url) {
        return MockMvcRequestBuilders.put(url).cookie(new Cookie("XSRF-TOKEN", TOKEN)).header("X-XSRF-TOKEN", TOKEN);
    }
}
