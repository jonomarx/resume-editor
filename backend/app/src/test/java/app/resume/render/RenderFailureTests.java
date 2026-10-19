package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.LatexAssembler;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            return new HttpRendererClient("http://127.0.0.1:" + server.getAddress().getPort(), 5, mapper).render("x");
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

    /** A reader polling while writers replace an entry must only ever see a complete old or complete new PDF. */
    @Test
    void cacheWritesAreAtomicReadersNeverSeeAPartialPdf(@TempDir Path dir) throws Exception {
        var cache = new DiskRenderCache(dir.toString(), mapper);
        String h = "b".repeat(64);
        byte[] small = new byte[1_000_000];
        byte[] big = new byte[12_000_000];
        java.util.Arrays.fill(small, (byte) 's');
        java.util.Arrays.fill(big, (byte) 'B');
        cache.store(1, h, "t", small, 1, "l");
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var bad = new AtomicInteger();
        var seen = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                cache.pdf(1, h).ifPresent(b -> {
                    seen.incrementAndGet();
                    if (b.length != small.length && b.length != big.length) bad.incrementAndGet();
                });
            }
        });
        reader.start();
        for (int i = 0; i < 6; i++) cache.store(1, h, "t", i % 2 == 0 ? big : small, 1, "l");
        stop.set(true);
        reader.join();
        assertThat(seen.get()).isPositive();
        assertThat(bad.get()).isZero();
        try (var files = java.nio.file.Files.list(dir.resolve("users/1/render"))) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.startsWith(".tmp-"));
        }
    }
}
