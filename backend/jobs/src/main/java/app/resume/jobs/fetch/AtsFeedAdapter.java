package app.resume.jobs.fetch;

import java.util.List;
import java.util.Optional;

/** Tier 0: reads a public ATS job feed instead of scraping the career page. */
public interface AtsFeedAdapter {
    /** The feed URL for a career page URL this adapter recognises, or empty if the page is not on this ATS. */
    Optional<String> feedUrl(String sourceUrl);

    /** Maps the feed body (JSON) to postings. */
    List<ExtractedPosting> parse(String feedBody, String sourceUrl);

    /** Convenience: fetch the feed through the (polite, robots-aware) fetcher and parse it. */
    default Optional<List<ExtractedPosting>> load(String sourceUrl, PageFetcher fetcher) {
        return feedUrl(sourceUrl).map(feed -> {
            FetchResult r = fetcher.fetch(feed);
            if (!r.ok()) throw new FetchException("ATS feed returned HTTP " + r.status() + ": " + feed);
            return parse(r.body(), sourceUrl);
        });
    }
}
