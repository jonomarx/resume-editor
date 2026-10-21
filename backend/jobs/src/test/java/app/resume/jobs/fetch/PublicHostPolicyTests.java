package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicHostPolicyTests {
    final PublicHostPolicy policy = new PublicHostPolicy(host -> {
        try {
            return switch (host) {
                case "rebind.example" -> new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")};
                case "meta.example" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
                case "public.example" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                case "v6.example" -> new InetAddress[] {InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")};
                case "bad.cafe" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
                default -> throw new FetchException("no such host");
            };
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/a", "http://LOCALHOST:8080/", "http://foo.localhost/", "http://127.0.0.1/", "http://127.1/",
            "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://0.0.0.0/", "http://10.1.2.3/",
            "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/", "http://100.127.255.254/", "http://198.18.0.1/", "http://224.0.0.1/", "http://255.255.255.255/",
            "http://[::1]/", "http://[::]/", "http://[fe80::1]/", "http://[fc00::1]/", "http://[fd12:3456::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.1]/", "http://[2001:db8::1]/", "http://[64:ff9b::7f00:1]/",
            "http://metadata.internal/", "http://printer.local/", "http://999.1.1.1/"})
    void literalChecksRefuseNonPublicTargets(String url) {
        assertThatThrownBy(() -> policy.checkLiteral(url)).as(url).isInstanceOf(UrlNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)", "example.com/a", "http:///x"})
    void literalChecksRefuseOtherSchemesAndMalformedUrls(String url) {
        assertThatThrownBy(() -> policy.checkLiteral(url)).isInstanceOf(UrlNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/", "http://93.184.216.34/", "https://bad.cafe/jobs", "http://[2606:2800:220:1::1]/",
            "http://8.8.8.8/", "http://172.32.0.1/", "http://100.63.255.255/", "http://192.0.3.1/"})
    void literalChecksAllowPublicTargets(String url) {
        assertThatCode(() -> policy.checkLiteral(url)).as(url).doesNotThrowAnyException();
    }

    @Test
    void namesAreResolvedAndEveryAddressMustBePublic() {
        assertThatCode(() -> policy.check("public.example")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("v6.example")).doesNotThrowAnyException();
        assertThatCode(() -> policy.check("bad.cafe")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.check("meta.example")).isInstanceOf(UrlNotAllowedException.class);
        assertThatThrownBy(() -> policy.check("rebind.example")).isInstanceOf(UrlNotAllowedException.class); // one bad address is enough
        assertThatThrownBy(() -> policy.check("10.0.0.1")).isInstanceOf(UrlNotAllowedException.class);
        assertThatThrownBy(() -> policy.check("::1")).isInstanceOf(UrlNotAllowedException.class);
        assertThatThrownBy(() -> policy.check("nope.example")).isInstanceOf(FetchException.class);
    }

    @Test
    void fetcherRefusesRefusedHostsBeforeAnyRequest() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            var fetcher = new JsoupPageFetcher("ua", new DomainRateLimiter(0), 2000, policy);
            assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/x"))
                    .isInstanceOf(UrlNotAllowedException.class);
            assertThat(hits).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void everyRedirectHopIsCheckedAgainAndHopsAreLimited() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            hits.incrementAndGet();
            if (path.equals("/robots.txt")) {
                ex.sendResponseHeaders(404, -1);
            } else if (path.equals("/to-metadata")) {
                ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
                ex.sendResponseHeaders(302, -1);
            } else if (path.equals("/loop")) {
                ex.getResponseHeaders().add("Location", "/loop");
                ex.sendResponseHeaders(302, -1);
            } else if (path.equals("/to-ok")) {
                ex.getResponseHeaders().add("Location", "/ok");
                ex.sendResponseHeaders(301, -1);
            } else {
                byte[] b = "fine".getBytes();
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        try {
            // The test server is on loopback, so use a policy that allows only that host and refuses the metadata address.
            HostPolicy loopbackOnly = host -> {
                if (!host.equals("127.0.0.1")) policy.check(host);
            };
            var fetcher = new JsoupPageFetcher("ua", new DomainRateLimiter(0), 2000, loopbackOnly);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(fetcher.fetch(base + "/to-ok").body()).isEqualTo("fine");
            assertThat(fetcher.fetch(base + "/to-ok").finalUrl()).isEqualTo(base + "/ok");
            assertThatThrownBy(() -> fetcher.fetch(base + "/to-metadata")).isInstanceOf(UrlNotAllowedException.class);
            hits.set(0);
            assertThatThrownBy(() -> fetcher.fetch(base + "/loop")).isInstanceOf(FetchException.class).hasMessageContaining("too many redirects");
            assertThat(hits.get()).isEqualTo(JsoupPageFetcher.MAX_REDIRECTS + 1);
        } finally {
            server.stop(0);
        }
    }

    // ---- the e2e/test opt-in (app.jobs.fetch.allow-private-hosts) ----

    @Test
    void defaultsRefusePrivateTargetsAndTheOptInAllowsThem() {
        for (PublicHostPolicy refusing : new PublicHostPolicy[] {new PublicHostPolicy(), new PublicHostPolicy(false)}) {
            assertThatThrownBy(() -> refusing.checkLiteral("http://127.0.0.1:9000/")).isInstanceOf(UrlNotAllowedException.class);
            assertThatThrownBy(() -> refusing.check("localhost")).isInstanceOf(UrlNotAllowedException.class);
        }
        PublicHostPolicy open = new PublicHostPolicy(true);
        assertThatCode(() -> open.checkLiteral("http://127.0.0.1:9000/jobs")).doesNotThrowAnyException();
        assertThatCode(() -> open.checkLiteral("http://localhost:9000/")).doesNotThrowAnyException();
        assertThatCode(() -> open.check("127.0.0.1")).doesNotThrowAnyException();
        assertThatCode(() -> open.check("10.0.0.5")).doesNotThrowAnyException();
        // the opt-in only relaxes addresses: other schemes and malformed URLs are still refused
        assertThatThrownBy(() -> open.checkLiteral("file:///etc/passwd")).isInstanceOf(UrlNotAllowedException.class);
        assertThatThrownBy(() -> open.checkLiteral("ftp://127.0.0.1/")).isInstanceOf(UrlNotAllowedException.class);
    }

    @Test
    void withTheOptInTheRealFetcherCrawlsALocalFixtureSite() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = ex.getRequestURI().getPath().equals("/robots.txt") ? new byte[0] : "fixture".getBytes();
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/robots.txt") ? 404 : 200, b.length == 0 ? -1 : b.length);
            if (b.length > 0) ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/careers";
            var refused = new JsoupPageFetcher("ua", new DomainRateLimiter(0), 2000, new PublicHostPolicy(false));
            assertThatThrownBy(() -> refused.fetch(url)).isInstanceOf(UrlNotAllowedException.class);
            var allowed = new JsoupPageFetcher("ua", new DomainRateLimiter(0), 2000, new PublicHostPolicy(true));
            assertThat(allowed.fetch(url).body()).isEqualTo("fixture");
        } finally {
            server.stop(0);
        }
    }
}
