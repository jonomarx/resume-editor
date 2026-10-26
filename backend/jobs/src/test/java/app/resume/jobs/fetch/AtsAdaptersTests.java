package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AtsAdaptersTests {
    final GreenhouseAdapter greenhouse = new GreenhouseAdapter();
    final LeverAdapter lever = new LeverAdapter();
    final AshbyAdapter ashby = new AshbyAdapter();

    static String fixture(String name) throws Exception {
        return JsonLdPostingAdapterTests.fixture(name);
    }

    @Test
    void feedUrlsAreDerivedFromCareerPageUrls() {
        assertThat(greenhouse.feedUrl("https://boards.greenhouse.io/airbnb")).contains(
                "https://boards-api.greenhouse.io/v1/boards/airbnb/jobs?content=true");
        assertThat(greenhouse.feedUrl("https://job-boards.greenhouse.io/airbnb/jobs/1?gh_src=x")).contains(
                "https://boards-api.greenhouse.io/v1/boards/airbnb/jobs?content=true");
        assertThat(lever.feedUrl("https://jobs.lever.co/acme/")).contains("https://api.lever.co/v0/postings/acme?mode=json");
        assertThat(lever.feedUrl("https://jobs.eu.lever.co/acme")).contains("https://api.eu.lever.co/v0/postings/acme?mode=json");
        assertThat(ashby.feedUrl("https://jobs.ashbyhq.com/acme")).contains("https://api.ashbyhq.com/posting-api/job-board/acme");
    }

    @Test
    void unrelatedOrMalformedUrlsAreNotRecognised() {
        for (AtsFeedAdapter a : List.of(greenhouse, lever, ashby)) {
            assertThat(a.feedUrl("https://example.com/careers")).isEmpty();
            assertThat(a.feedUrl("https://evil.example/boards.greenhouse.io/x")).isEmpty();
            assertThat(a.feedUrl("not a url")).isEmpty();
            assertThat(a.feedUrl("https://boards.greenhouse.io/")).isEmpty();
        }
        assertThat(greenhouse.feedUrl("https://boards.greenhouse.io/a%20b")).isEmpty();
    }

    @Test
    void greenhouseMapsFeed() throws Exception {
        List<ExtractedPosting> r = greenhouse.parse(fixture("greenhouse-feed.json"), "https://boards.greenhouse.io/airbnb");
        assertThat(r).hasSize(2); // the untitled entry is dropped
        ExtractedPosting p = r.get(0);
        assertThat(p.title()).isEqualTo("Software Engineer Intern, Summer 2027");
        assertThat(p.company()).isEqualTo("Airbnb");
        assertThat(p.location()).isEqualTo("Seattle, WA");
        assertThat(p.url()).isEqualTo("https://careers.example.com/positions/7789554?gh_jid=7789554");
        assertThat(p.datePosted()).isEqualTo("2026-04-13T04:49:05-04:00");
        assertThat(p.description()).contains("Join us.").contains("Java").contains("SQL").doesNotContain("<").doesNotContain("&lt;");
        assertThat(r.get(1).location()).isNull();
        assertThat(r.get(1).description()).isNull();
    }

    @Test
    void leverMapsFeed() throws Exception {
        List<ExtractedPosting> r = lever.parse(fixture("lever-feed.json"), "https://jobs.lever.co/acme");
        assertThat(r).hasSize(2);
        ExtractedPosting p = r.get(0);
        assertThat(p.title()).isEqualTo("Data Intern");
        assertThat(p.company()).isEqualTo("acme");
        assertThat(p.location()).isEqualTo("Austin, TX; Denver, CO; Remote");
        assertThat(p.employmentType()).isEqualTo("Intern");
        assertThat(p.url()).isEqualTo("https://jobs.lever.co/acme/ad208490");
        assertThat(p.datePosted()).startsWith("2026-");
        assertThat(p.description()).contains("Work with data.").contains("Requirements:").contains("Python").contains("equal opportunity");
        assertThat(r.get(1).location()).isNull();
    }

    @Test
    void ashbyMapsFeedAndSkipsUnlisted() throws Exception {
        List<ExtractedPosting> r = ashby.parse(fixture("ashby-feed.json"), "https://jobs.ashbyhq.com/acme");
        assertThat(r).extracting(ExtractedPosting::title).containsExactly("ML Intern", "HTML Only");
        assertThat(r.get(0).location()).isEqualTo("New York; Remote - US");
        assertThat(r.get(0).employmentType()).isEqualTo("Intern");
        assertThat(r.get(0).description()).isEqualTo("Train models.");
        assertThat(r.get(1).description()).isEqualTo("One\nTwo");
    }

    @Test
    void unexpectedShapesYieldNothingAndInvalidJsonFails() {
        assertThat(greenhouse.parse("{\"nope\":1}", "https://boards.greenhouse.io/x")).isEmpty();
        assertThat(lever.parse("{\"nope\":1}", "https://jobs.lever.co/x")).isEmpty();
        assertThat(ashby.parse("{\"nope\":1}", "https://jobs.ashbyhq.com/x")).isEmpty();
        assertThatThrownBy(() -> greenhouse.parse("<html>", "https://boards.greenhouse.io/x")).isInstanceOf(FetchException.class);
    }

    @Test
    void loadFetchesTheFeedThroughThePageFetcher() throws Exception {
        List<String> asked = new java.util.ArrayList<>();
        PageFetcher fake = url -> {
            asked.add(url);
            return new FetchResult(url, url, 200, "application/json", uncheck(() -> fixture("lever-feed.json")));
        };
        Optional<List<ExtractedPosting>> r = lever.load("https://jobs.lever.co/acme", fake);
        assertThat(r).isPresent();
        assertThat(r.get()).hasSize(2);
        assertThat(asked).containsExactly("https://api.lever.co/v0/postings/acme?mode=json");
        assertThat(lever.load("https://example.com/x", fake)).isEmpty();
        PageFetcher broken = url -> new FetchResult(url, url, 404, "text/html", "");
        assertThatThrownBy(() -> lever.load("https://jobs.lever.co/acme", broken)).isInstanceOf(FetchException.class);
    }

    interface Thrower<T> { T get() throws Exception; }

    static <T> T uncheck(Thrower<T> t) {
        try {
            return t.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
