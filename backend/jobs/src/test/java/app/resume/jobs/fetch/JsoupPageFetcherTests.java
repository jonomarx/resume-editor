package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the real jsoup fetcher against a local HTTP server (no outside network). */
class JsoupPageFetcherTests {
    HttpServer server;
    String base;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    String robots = "User-agent: *\nDisallow: /private\n";
    int robotsStatus = 200;
    final List<Long> sleeps = new ArrayList<>();
    JsoupPageFetcher fetcher;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath());
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            String path = ex.getRequestURI().getPath();
            int status = 200;
            String body = "<html>ok " + path + "</html>";
            if (path.equals("/robots.txt")) {
                status = robotsStatus;
                body = robots;
            } else if (path.equals("/gone")) {
                status = 410;
                body = "gone";
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        fetcher = new JsoupPageFetcher(JsoupPageFetcher.DEFAULT_USER_AGENT,
                new DomainRateLimiter(250, System::currentTimeMillis, sleeps::add), 5000);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fetchesPageWithIdentifyingUserAgent() {
        FetchResult r = fetcher.fetch(base + "/jobs");
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains("ok /jobs");
        assertThat(userAgents).isNotEmpty().allMatch(ua -> ua.startsWith("ResumeEditorBot/"));
    }

    @Test
    void errorStatusesAreReturnedNotThrown() {
        FetchResult r = fetcher.fetch(base + "/gone");
        assertThat(r.status()).isEqualTo(410);
        assertThat(r.ok()).isFalse();
    }

    @Test
    void robotsDisallowedPathIsNeverRequested() {
        assertThatThrownBy(() -> fetcher.fetch(base + "/private/job/1")).isInstanceOf(RobotsDisallowedException.class);
        assertThat(requests).containsExactly("/robots.txt");
    }

    @Test
    void robotsIsFetchedOncePerHost() {
        fetcher.fetch(base + "/a");
        fetcher.fetch(base + "/b");
        assertThat(requests).containsExactly("/robots.txt", "/a", "/b");
    }

    @Test
    void missingRobotsAllowsEverythingButServerErrorBlocks() {
        robotsStatus = 404;
        assertThat(fetcher.fetch(base + "/private/x").ok()).isTrue();

        robotsStatus = 503;
        var strict = new JsoupPageFetcher(JsoupPageFetcher.DEFAULT_USER_AGENT, new DomainRateLimiter(0), 5000);
        assertThatThrownBy(() -> strict.fetch(base + "/jobs")).isInstanceOf(RobotsDisallowedException.class);
    }

    @Test
    void requestsToOneHostAreRateLimitedIncludingRobots() {
        fetcher.fetch(base + "/a"); // robots.txt + /a: the second request must wait
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(1L, 250L);
    }

    @Test
    void badUrlsAndUnreachableHostsAreFetchExceptions() {
        assertThatThrownBy(() -> fetcher.fetch("ftp://x.example/a")).isInstanceOf(FetchException.class);
        assertThatThrownBy(() -> fetcher.fetch("not a url")).isInstanceOf(FetchException.class);
        server.stop(0);
        assertThatThrownBy(() -> new JsoupPageFetcher("ua", new DomainRateLimiter(0), 1000).fetch(base + "/x"))
                .isInstanceOf(FetchException.class);
    }
}
