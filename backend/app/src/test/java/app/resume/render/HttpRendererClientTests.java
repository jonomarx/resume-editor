package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** One test per outcome mapping of the RestClient-based renderer client, plus the timeout settings. */
@SpringBootTest(properties = {"app.renderer.timeout-seconds=7", "app.renderer.connect-timeout-seconds=2"})
@ActiveProfiles("test")
class HttpRendererClientTests {
    @Autowired RendererProperties props;
    @Autowired ObjectMapper mapper;

    record Fixture(HttpRendererClient client, MockRestServiceServer server) {}

    Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://renderer.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new HttpRendererClient(builder.build(), mapper), server);
    }

    @Test
    void postsTheTexAsUtf8TextToRender() {
        Fixture f = fixture();
        f.server.expect(requestTo("http://renderer.test/render")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "text/plain;charset=UTF-8")).andExpect(content().bytes("café \\LaTeX".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .andRespond(withStatus(HttpStatus.OK).header("X-Page-Count", "1").body(new byte[] {1}));
        assertThat(f.client.render("café \\LaTeX")).isInstanceOf(RendererClient.Pdf.class);
        f.server.verify();
    }

    @Test
    void ok200WithAPageCountIsAPdf() {
        Fixture f = fixture();
        f.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.OK).header("X-Page-Count", " 2 ")
                .contentType(MediaType.APPLICATION_PDF).body("%PDF-1".getBytes()));
        assertThat(f.client.render("x")).isInstanceOfSatisfying(RendererClient.Pdf.class, p -> {
            assertThat(p.pageCount()).isEqualTo(2);
            assertThat(new String(p.bytes())).isEqualTo("%PDF-1");
        });
    }

    @Test
    void aMissingNonNumericOrNonPositivePageCountIsARendererFailure() {
        for (String header : new String[] {null, "abc", "0", "-1", ""}) {
            Fixture f = fixture();
            var resp = withStatus(HttpStatus.OK).body(new byte[] {1});
            if (header != null) resp = resp.header("X-Page-Count", header);
            f.server.expect(requestTo("http://renderer.test/render")).andRespond(resp);
            assertThat(f.client.render("x")).as("header " + header).isInstanceOf(RendererClient.Unavailable.class);
        }
    }

    @Test
    void a422CarriesTheCompileLog() {
        Fixture f = fixture();
        f.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body("{\"log\":\"! Undefined control sequence.\\nl.5 \\\\boom\"}"));
        assertThat(f.client.render("x")).isInstanceOfSatisfying(RendererClient.CompileError.class,
                e -> assertThat(e.log()).isEqualTo("! Undefined control sequence.\nl.5 \\boom"));
        Fixture g = fixture();
        g.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(MediaType.APPLICATION_JSON).body("{}"));
        assertThat(g.client.render("x")).isInstanceOfSatisfying(RendererClient.CompileError.class, e -> assertThat(e.log()).isEmpty());
    }

    @Test
    void a413IsTooLargeA504IsATimeoutAndOtherStatusesAreUnavailable() {
        Fixture f = fixture();
        f.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(f.client.render("x")).isInstanceOf(RendererClient.TooLarge.class);
        Fixture g = fixture();
        g.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(g.client.render("x")).isInstanceOfSatisfying(RendererClient.Unavailable.class, u -> assertThat(u.reason()).contains("timed out"));
        Fixture h = fixture();
        h.server.expect(requestTo("http://renderer.test/render")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(h.client.render("x")).isInstanceOfSatisfying(RendererClient.Unavailable.class, u -> assertThat(u.reason()).contains("500"));
    }

    @Test
    void aConnectionErrorIsUnavailable() {
        Fixture f = fixture();
        f.server.expect(requestTo("http://renderer.test/render")).andRespond(request -> { throw new IOException("connection refused"); });
        assertThat(f.client.render("x")).isInstanceOf(RendererClient.Unavailable.class);
    }

    @Test
    void timeoutPropertiesBindFromApplicationYmlAndOverrides() {
        assertThat(props.timeoutSeconds()).isEqualTo(7);        // overridden by the test property
        assertThat(props.connectTimeoutSeconds()).isEqualTo(2); // overridden by the test property
        assertThat(new RendererProperties("http://x", 30, 3).url()).isEqualTo("http://x");
    }

    @Test
    void theReadTimeoutReachesTheHttpClient() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/render", ex -> {
            try { Thread.sleep(4000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        slow.start();
        try {
            var client = new HttpRendererClient(RestClient.builder(),
                    new RendererProperties("http://127.0.0.1:" + slow.getAddress().getPort(), 1, 1), mapper);
            long start = System.nanoTime();
            assertThat(client.render("x")).isInstanceOf(RendererClient.Unavailable.class);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3500); // gave up after about 1 s, not 4
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void theDefaultsAreAppliedWhenNothingIsConfigured() {
        // the record defaults apply when the keys are absent (connect timeout 3 s, read timeout 30 s)
        var bound = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                        java.util.Map.of("app.renderer.url", "http://r"))).bind("app.renderer", RendererProperties.class).get();
        assertThat(bound.timeoutSeconds()).isEqualTo(30);
        assertThat(bound.connectTimeoutSeconds()).isEqualTo(3);
    }
}
