package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonLdPostingAdapterTests {
    final JsonLdPostingAdapter adapter = new JsonLdPostingAdapter();

    static String fixture(String name) throws IOException {
        try (var in = JsonLdPostingAdapterTests.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void singleJobPostingWithEscapedHtmlDescription() throws Exception {
        List<ExtractedPosting> r = adapter.extract(fixture("jsonld-single.html"), "https://acme.example/careers");
        assertThat(r).hasSize(1);
        ExtractedPosting p = r.get(0);
        assertThat(p.title()).isEqualTo("Software Engineer Intern, Summer 2027");
        assertThat(p.company()).isEqualTo("Acme Corp");
        assertThat(p.location()).isEqualTo("Seattle, WA, US");
        assertThat(p.url()).isEqualTo("https://acme.example/jobs/123?utm_source=x"); // resolved, not yet normalized
        assertThat(p.datePosted()).isEqualTo("2026-09-01");
        assertThat(p.employmentType()).isEqualTo("INTERN,FULL_TIME");
        assertThat(p.description()).contains("Build things.").contains("Java").contains("SQL").doesNotContain("<");
    }

    @Test
    void graphArraysItemListAndMalformedBlocks() throws Exception {
        List<ExtractedPosting> r = adapter.extract(fixture("jsonld-graph.html"), "https://beta.example/");
        assertThat(r).extracting(ExtractedPosting::title).containsExactlyInAnyOrder("Data Intern", "ML Intern", "Ops Intern");
        ExtractedPosting data = r.stream().filter(p -> p.title().equals("Data Intern")).findFirst().orElseThrow();
        assertThat(data.company()).isEqualTo("Beta");
        assertThat(data.location()).isEqualTo("Remote");
        assertThat(data.description()).isEqualTo("Plain text");
        ExtractedPosting ml = r.stream().filter(p -> p.title().equals("ML Intern")).findFirst().orElseThrow();
        assertThat(ml.location()).isEqualTo("Austin, TX; Remote, US");
        assertThat(r.stream().filter(p -> p.title().equals("Ops Intern")).findFirst().orElseThrow().url())
                .isEqualTo("https://beta.example/jobs/9");
    }

    @Test
    void pageWithoutJsonLdYieldsNothing() throws Exception {
        assertThat(adapter.extract(fixture("no-jsonld.html"), "https://x.example/")).isEmpty();
    }

    @Test
    void postingTextIsDataNotInstructions() throws Exception {
        // Text outside the JSON-LD block (the injected sentence in the fixture body) never reaches a field.
        assertThat(adapter.extract(fixture("jsonld-single.html"), "https://acme.example/"))
                .noneMatch(p -> String.valueOf(p.description()).contains("Ignore previous"));
    }
}
