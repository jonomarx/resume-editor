package app.resume.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resume.jobs.PostingView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** US-051: scoring of the tag-overlap matcher (MATCHER=tags) on fixture postings that carry tags. */
class TagOverlapMatcherTests {
    final Matcher matcher = new TagOverlapMatcher();

    static PostingView full(long id, String location, String season, String auth, String year, String... tags) {
        Instant t = Instant.parse("2026-10-20T00:00:00Z");
        return new PostingView(id, "T" + id, "C", location, "https://x/" + id, "d", List.of(), List.of(), List.of(tags), season, auth,
                year, "active", "crawl", null, null, t, t);
    }

    static PostingView p(long id, String... tags) {
        return full(id, null, null, null, null, tags);
    }

    Map<Long, Matcher.Match> run(Set<String> master, List<PostingView> corpus, Matcher.HardFilters h) {
        return matcher.match(master, corpus, corpus, h);
    }

    @Test
    void rareTagsCountMoreThanCommonOnes() {
        // java is on every posting, rust on one: covering rust is worth more than covering java
        List<PostingView> corpus = List.of(p(1, "java", "rust"), p(2, "java", "go"), p(3, "java", "go"), p(4, "java", "go"));
        int withRust = run(Set.of("rust"), corpus, Matcher.HardFilters.NONE).get(1L).score();
        int withJava = run(Set.of("java"), corpus, Matcher.HardFilters.NONE).get(1L).score();
        assertThat(withRust).isGreaterThan(withJava);
        assertThat(withRust).isBetween(1, 99);
    }

    @Test
    void fullCoverageIs100AndNothingOrNoTagsIsZero() {
        List<PostingView> corpus = List.of(p(1, "java", "sql"), p(2), p(3, "go"));
        Map<Long, Matcher.Match> m = run(Set.of("java", "sql"), corpus, Matcher.HardFilters.NONE);
        assertThat(m.get(1L).score()).isEqualTo(100);
        assertThat(m.get(1L).matchedTags()).containsExactly("java", "sql");
        assertThat(m.get(2L).score()).isZero(); // posting without tags
        assertThat(m.get(3L).score()).isZero();
        assertThat(m.get(3L).missingTags()).containsExactly("go");
        assertThat(run(Set.of(), corpus, Matcher.HardFilters.NONE).values()).allMatch(x -> x.score() == 0);
    }

    @Test
    void scoreDoesNotDependOnWhichCandidatesAreListed() {
        List<PostingView> corpus = List.of(p(1, "java", "sql"), p(2, "java"), p(3, "go"));
        int all = matcher.match(Set.of("sql"), corpus, corpus, Matcher.HardFilters.NONE).get(1L).score();
        int one = matcher.match(Set.of("sql"), List.of(corpus.get(0)), corpus, Matcher.HardFilters.NONE).get(1L).score();
        assertThat(one).isEqualTo(all);
    }

    @Test
    void hardFiltersExcludeMismatchesButKeepPostingsThatDoNotSay() {
        List<PostingView> corpus = List.of(
                full(1, "Seattle, WA", "Summer 2027", "US citizens only", "Junior", "java"),
                full(2, "Austin, TX", "Summer 2027", null, null, "java"),
                full(3, null, null, null, null, "java"),
                full(4, "Seattle, WA", "Fall 2027", null, null, "java"));
        assertThat(run(Set.of("java"), corpus, new Matcher.HardFilters("summer 2027", null, null, null)).keySet())
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(run(Set.of("java"), corpus, new Matcher.HardFilters(null, "US citizens only", null, null)).keySet())
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(run(Set.of("java"), corpus, new Matcher.HardFilters(null, null, "senior", null)).keySet())
                .containsExactlyInAnyOrder(2L, 3L, 4L);
        assertThat(run(Set.of("java"), corpus, new Matcher.HardFilters(null, null, null, "seattle")).keySet())
                .containsExactlyInAnyOrder(1L, 3L, 4L);
        assertThat(run(Set.of("java"), corpus, new Matcher.HardFilters("summer 2027", null, null, "seattle")).keySet())
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void matcherIsChosenByNameAndUnknownStopsStartup() {
        assertThat(new MatcherConfig().matcher("tags")).isInstanceOf(TagOverlapMatcher.class);
        assertThatThrownBy(() -> new MatcherConfig().matcher("magic")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown MATCHER");
    }
}
