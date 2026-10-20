package app.resume.jobs.validity;

import app.resume.jobs.fetch.FetchException;
import app.resume.jobs.fetch.FetchResult;
import app.resume.jobs.fetch.PageFetcher;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;

/**
 * Decides whether a posting URL is still live: closed on HTTP 404 or 410, or when the visible page text says the
 * posting is no longer accepting applications. Anything uncertain (network error, robots, 5xx, 403, 429) is UNKNOWN
 * so a flaky site cannot close postings by accident. Page text is only searched, never interpreted.
 */
public class PostingValidityChecker {
    /** Lower-case phrases (whitespace collapsed) that mean the posting is closed. */
    static final List<String> CLOSED_PHRASES = List.of(
            "no longer accepting applications",
            "no longer accepting application",
            "this job is no longer available",
            "this position is no longer available",
            "this position has been filled",
            "this job has expired",
            "this posting has expired",
            "job posting is no longer active",
            "applications are closed",
            "applications for this position are closed");

    private final PageFetcher fetcher;

    public PostingValidityChecker(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public Verdict check(String url) {
        FetchResult r;
        try {
            r = fetcher.fetch(url);
        } catch (FetchException e) {
            return Verdict.unknown(e.getMessage());
        }
        if (r.status() == 404 || r.status() == 410) return Verdict.closed("http " + r.status());
        if (!r.ok()) return Verdict.unknown("http " + r.status());
        String text = Jsoup.parse(r.body() == null ? "" : r.body()).text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String phrase : CLOSED_PHRASES) {
            if (text.contains(phrase)) return Verdict.closed("page says: " + phrase);
        }
        return Verdict.open();
    }
}
