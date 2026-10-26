package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.enrich.VocabularyTagMatcher.Mode;
import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaxonomyAliasesTests {
    static VocabularyTag tag(long id, String name, String... aliases) {
        return new VocabularyTag(id, name, Slugs.slug(name), VocabularyCategory.LANGUAGE, List.of(aliases));
    }

    @Test
    void bundledSourcesGiveKnownAliasesOnlyToTagsTheUserHas() {
        var all = TaxonomyAliases.shared(TaxonomyAliases.ALL_SOURCES);
        assertThat(all.aliasesFor(tag(1, "Go"))).contains("golang");
        assertThat(all.aliasesFor(tag(2, "C++"))).contains("cpp");
        assertThat(all.aliasesFor(tag(3, "AWS"))).contains("Amazon Web Services");
        assertThat(all.aliasesFor(tag(4, "SQL"))).contains("Structured query language");
        assertThat(all.aliasesFor(tag(5, "Kubernetes"))).contains("K8s");
        assertThat(all.aliasesFor(tag(6, "Underwater Basket Weaving"))).isEmpty();
        // a tag whose alias is the key also gets the aliases
        assertThat(all.aliasesFor(tag(7, "Amazon Cloud", "AWS"))).contains("Amazon Web Services");
        // the primary sources do not include O*NET
        assertThat(TaxonomyAliases.shared(TaxonomyAliases.PRIMARY_SOURCES).aliasesFor(tag(3, "AWS"))).doesNotContain("Amazon Web Services");
    }

    @Test
    void aliasesBecomeMatchesForTheirTagOnly() {
        var vocab = List.of(tag(1, "Go"), tag(2, "AWS"), tag(3, "Kubernetes"));
        var m = new VocabularyTagMatcher(vocab, TaxonomyAliases.shared(TaxonomyAliases.ALL_SOURCES));
        assertThat(m.match("Experience with Amazon Web Services", Mode.GUARDED)).containsExactly(2L);
        assertThat(m.match("Experience with K8s", Mode.GUARDED)).containsExactly(3L);
        assertThat(new VocabularyTagMatcher(vocab).match("Experience with Amazon Web Services", Mode.GUARDED)).isEmpty();
    }

    @Test
    void ambiguityGuardsApplyToTaxonomyAliases() {
        var taxonomy = new TaxonomyAliases(Map.of("tagx", List.of("cake", "Fast Thing", "es")));
        var m = new VocabularyTagMatcher(List.of(tag(1, "TagX")), taxonomy);
        assertThat(m.match("We love a good cake at lunch", Mode.GUARDED)).isEmpty();      // short word: needs exact case and a skill cue
        assertThat(m.match("Experience with cake", Mode.GUARDED)).containsExactly(1L);
        assertThat(m.match("fast thing and Fast Thing", Mode.GUARDED)).containsExactly(1L); // multi-word alias is unambiguous
        assertThat(m.match("es", Mode.GUARDED)).isEmpty();
    }

    @Test
    void aProvidedAliasOfAnotherTagIsNeverStolen() {
        var taxonomy = new TaxonomyAliases(Map.of("javascript", List.of("node", "JS Language")));
        var vocab = List.of(tag(1, "JavaScript"), tag(2, "Node.js", "node"));
        var m = new VocabularyTagMatcher(vocab, taxonomy);
        assertThat(m.match("Experience with node", Mode.GUARDED)).containsExactly(2L);
        assertThat(m.match("JS Language", Mode.GUARDED)).containsExactly(1L);
    }

    @Test
    void malformedAndOversizedDataIsSkipped() {
        var big = new ArrayList<String>();
        big.add("x".repeat(500));
        big.add(null);
        big.add(" ");
        big.add("ok alias");
        var t = new TaxonomyAliases(Map.of("k", big, "y".repeat(500), List.of("zzz")));
        assertThat(t.aliasesFor(tag(1, "K"))).containsExactly("ok alias");
        assertThat(t.size()).isEqualTo(1);
        assertThat(TaxonomyAliases.shared(List.of("does-not-exist.json")).size()).isZero();
    }

    @Test
    void theTaxonomyDataFilesStaySmall() throws Exception {
        for (String f : TaxonomyAliases.ALL_SOURCES) {
            try (var in = getClass().getResourceAsStream("/taxonomy/" + f)) {
                assertThat(in.readAllBytes().length).as(f).isLessThan(20_000);
            }
        }
        assertThat(Set.of(1)).isNotEmpty();
    }
}
