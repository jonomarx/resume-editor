package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.LatexAssembler;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Renderer protocol violations and cache failures are 503-type failures and are never cached. */
class RenderFailureTests {
    final ObjectMapper mapper = new ObjectMapper();
    final StarterTree starter = new StarterTree(mapper);

    RendererClient.Outcome serve(String pageHeader) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/render", ex -> {
            byte[] body = "%PDF-x".getBytes();
            if (pageHeader != null) ex.getResponseHeaders().add("X-Page-Count", pageHeader);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            return new HttpRendererClient(org.springframework.web.client.RestClient.builder(),
                    new RendererProperties("http://127.0.0.1:" + server.getAddress().getPort(), 5, 3), mapper).render("x");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissingNonNumericOrNonPositivePageCountIsARendererFailure() throws Exception {
        assertThat(serve(null)).isInstanceOf(RendererClient.Unavailable.class);
        assertThat(serve("abc")).isInstanceOf(RendererClient.Unavailable.class);
        assertThat(serve("0")).isInstanceOf(RendererClient.Unavailable.class);
        assertThat(serve("-2")).isInstanceOf(RendererClient.Unavailable.class);
        assertThat(serve("3")).isInstanceOfSatisfying(RendererClient.Pdf.class, p -> assertThat(p.pageCount()).isEqualTo(3));
    }

    @Test
    void aPdfWithoutAPageCountIsUnavailableAndNotCached() {
        var stored = new AtomicInteger();
        RenderCache cache = new RenderCache() {
            public Optional<Entry> find(long u, String h, String f) { return Optional.empty(); }
            public void store(long u, String h, String f, byte[] p, int n, String l) { stored.incrementAndGet(); }
            public Optional<byte[]> pdf(long u, String h) { return Optional.empty(); }
        };
        var service = new RenderService(new LatexAssembler(), tex -> new RendererClient.Pdf(new byte[] {1}, null, ""), cache, 1_000_000);
        assertThat(service.render(1, starter.create())).isInstanceOf(RenderService.Unavailable.class);
        assertThat(stored.get()).isZero();
    }

    @Test
    void aCacheWriteFailureIs503NotAnExceptionAndLeavesNothingCached() {
        RenderCache failing = new RenderCache() {
            public Optional<Entry> find(long u, String h, String f) { return Optional.empty(); }
            public void store(long u, String h, String f, byte[] p, int n, String l) {
                throw new java.io.UncheckedIOException(new java.io.IOException("disk full"));
            }
            public Optional<byte[]> pdf(long u, String h) { return Optional.empty(); }
        };
        var service = new RenderService(new LatexAssembler(), tex -> new RendererClient.Pdf(new byte[] {1}, 1, ""), failing, 1_000_000);
        assertThat(service.render(1, starter.create())).isInstanceOf(RenderService.Unavailable.class);
    }
}
