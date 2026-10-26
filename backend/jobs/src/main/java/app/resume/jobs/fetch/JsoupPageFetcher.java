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
    private final HostPolicy hostPolicy;
    static final int MAX_REDIRECTS = 5;
    private final ConcurrentMap<String, RobotsRules> robotsByOrigin = new ConcurrentHashMap<>();

    /** Production constructor: public hosts only ({@link PublicHostPolicy}). */
    public JsoupPageFetcher(String userAgent, DomainRateLimiter limiter, int timeoutMillis) {
        this(userAgent, limiter, timeoutMillis, new PublicHostPolicy());
    }

    public JsoupPageFetcher(String userAgent, DomainRateLimiter limiter, int timeoutMillis, HostPolicy hostPolicy) {
        this.userAgent = userAgent;
        this.limiter = limiter;
        this.timeoutMillis = timeoutMillis;
        this.hostPolicy = hostPolicy;
    }

    /**
     * Follows at most {@value #MAX_REDIRECTS} redirects by hand so that every hop (scheme, host, addresses, robots.txt)
     * is checked like the first request.
     */
    @Override
    public FetchResult fetch(String url) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parse(current);
            hostPolicy.check(uri.getHost());
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            RobotsRules rules = robotsByOrigin.computeIfAbsent(origin, this::loadRobots);
            String pathAndQuery = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (!rules.allows(pathAndQuery)) throw new RobotsDisallowedException(current);
            Connection.Response r = get(current, uri.getHost());
            String location = r.statusCode() >= 300 && r.statusCode() < 400 ? r.header("Location") : null;
            if (location == null) return new FetchResult(url, current, r.statusCode(), r.contentType(), r.body());
            try {
                current = uri.resolve(location.strip()).toString();
            } catch (IllegalArgumentException e) {
                throw new FetchException("bad redirect target from " + current, e);
            }
        }
        throw new FetchException("too many redirects: " + url);
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
            return Jsoup.connect(url).userAgent(userAgent).timeout(timeoutMillis).followRedirects(false)
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
