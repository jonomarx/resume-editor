package app.resume.jobs.fetch;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jsoup.Connection;
import org.jsoup.Jsoup;

/**
 * jsoup-based {@link PageFetcher}: identifying user agent, robots.txt honored (cached per host), per-domain rate
 * limit. Pages and robots.txt are fetched through the same limiter.
 */
public class JsoupPageFetcher implements PageFetcher {
    public static final String AGENT_TOKEN = "ResumeEditorBot";
    public static final String DEFAULT_USER_AGENT = AGENT_TOKEN + "/1.0 (+personal job tracker; respects robots.txt)";
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private final String userAgent;
    private final DomainRateLimiter limiter;
    private final int timeoutMillis;
    private final ConcurrentMap<String, RobotsRules> robotsByOrigin = new ConcurrentHashMap<>();

    public JsoupPageFetcher(String userAgent, DomainRateLimiter limiter, int timeoutMillis) {
        this.userAgent = userAgent;
        this.limiter = limiter;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public FetchResult fetch(String url) {
        URI uri = parse(url);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        RobotsRules rules = robotsByOrigin.computeIfAbsent(origin, this::loadRobots);
        String pathAndQuery = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        if (!rules.allows(pathAndQuery)) throw new RobotsDisallowedException(url);
        Connection.Response r = get(url, uri.getHost());
        return new FetchResult(url, r.url().toString(), r.statusCode(), r.contentType(), r.body());
    }

    private RobotsRules loadRobots(String origin) {
        try {
            Connection.Response r = get(origin + "/robots.txt", URI.create(origin).getHost());
            // 4xx: no robots.txt, everything allowed. 5xx: be conservative and treat the site as closed to us.
            if (r.statusCode() >= 500) return RobotsRules.parse("User-agent: *\nDisallow: /", AGENT_TOKEN);
            if (r.statusCode() >= 400) return RobotsRules.ALLOW_ALL;
            return RobotsRules.parse(r.body(), AGENT_TOKEN);
        } catch (FetchException e) {
            return RobotsRules.parse("User-agent: *\nDisallow: /", AGENT_TOKEN);
        }
    }

    private Connection.Response get(String url, String host) {
        limiter.acquire(host);
        try {
            return Jsoup.connect(url).userAgent(userAgent).timeout(timeoutMillis).followRedirects(true)
                    .ignoreHttpErrors(true).ignoreContentType(true).maxBodySize(MAX_BODY_BYTES).execute();
        } catch (IOException | IllegalArgumentException e) {
            throw new FetchException("fetch failed: " + url, e);
        }
    }

    private static URI parse(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                throw new FetchException("not an http(s) url: " + url);
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FetchException("invalid url: " + url, e);
        }
    }
}
