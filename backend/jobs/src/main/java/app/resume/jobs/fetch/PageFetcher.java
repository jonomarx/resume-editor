package app.resume.jobs.fetch;

/** Fetches one page. Implementations are chosen by configuration (jsoup now, a headless browser later, US-036). */
public interface PageFetcher {
    /**
     * @throws RobotsDisallowedException if robots.txt forbids fetching the URL
     * @throws FetchException on network failure or an unusable URL
     */
    FetchResult fetch(String url);
}
