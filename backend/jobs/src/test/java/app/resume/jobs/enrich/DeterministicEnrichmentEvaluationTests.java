package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * The method comparison for US-109 (docs/decision-003-deterministic-enrichment.md): precision and recall of each tag method
 * on the labelled fixture set, and exact-match accuracy of the field rules. The printed table is the evidence on the card.
 * The assertions pin the numbers so a regression in the chosen method fails the build.
 */
class DeterministicEnrichmentEvaluationTests {
    record Case(String id, String source, String title, String text, Set<Long> tags, String season, String classYear, String auth,
            Boolean hasBasic, Boolean hasPreferred) {}

    record Score(int tp, int fp, int fn) {
        double precision() { return tp + fp == 0 ? 1 : (double) tp / (tp + fp); }
        double recall() { return tp + fn == 0 ? 1 : (double) tp / (tp + fn); }
        double f1() { double p = precision(), r = recall(); return p + r == 0 ? 0 : 2 * p * r / (p + r); }
    }

    final List<VocabularyTag> vocabulary = new ArrayList<>();
    final List<Case> cases = new ArrayList<>();
    final java.util.Map<String, Long> ids = new java.util.HashMap<>();

    DeterministicEnrichmentEvaluationTests() throws Exception {
        JsonNode root = new ObjectMapper().readTree(getClass().getResourceAsStream("/fixtures/enrichment/labelled-postings.json"));
        long next = 1;
        for (JsonNode v : root.get("vocabulary")) {
            List<String> aliases = new ArrayList<>();
            v.get("aliases").forEach(a -> aliases.add(a.asText()));
            String name = v.get("name").asText();
            vocabulary.add(new VocabularyTag(next, name, Slugs.slug(name), category(v.get("category").asText()), aliases));
            ids.put(name, next++);
        }
        for (JsonNode p : root.get("postings")) {
            Set<Long> tags = new HashSet<>();
            p.get("tags").forEach(t -> tags.add(ids.get(t.asText())));
            cases.add(new Case(p.get("id").asText(), p.get("source").asText(), p.get("title").asText(), p.get("text").asText(), tags,
                    str(p, "season"), str(p, "classYear"), str(p, "workAuthorization"), bool(p, "hasBasic"), bool(p, "hasPreferred")));
        }
    }

    static VocabularyCategory category(String v) {
        for (VocabularyCategory c : VocabularyCategory.values()) if (c.value().equals(v)) return c;
        throw new IllegalArgumentException(v);
    }

    static String str(JsonNode n, String f) { return n.get(f).isNull() ? null : n.get(f).asText(); }

    static Boolean bool(JsonNode n, String f) { return n.get(f).isNull() ? null : n.get(f).asBoolean(); }

    Score tagScore(BiFunction<VocabularyTagMatcher, Case, Set<Long>> method) {
        VocabularyTagMatcher matcher = new VocabularyTagMatcher(vocabulary);
        int tp = 0, fp = 0, fn = 0;
        for (Case c : cases) {
            Set<Long> got = method.apply(matcher, c);
            for (Long g : got) if (c.tags.contains(g)) tp++; else fp++;
            for (Long w : c.tags) if (!got.contains(w)) fn++;
        }
        return new Score(tp, fp, fn);
    }

    String names(Set<Long> idSet) {
        List<String> n = new ArrayList<>();
        ids.forEach((k, v) -> { if (idSet.contains(v)) n.add(k); });
        java.util.Collections.sort(n);
        return n.toString();
    }

    @Test
    void tagMethodsCompared() {
        Score naive = tagScore((m, c) -> m.match(c.title + "\n" + c.text, VocabularyTagMatcher.Mode.NAIVE));
        Score full = tagScore((m, c) -> DeterministicEnricher.tags(m, DeterministicEnricher.TagStrategy.FULL_TEXT, c.title, c.text));
        Score scoped = tagScore((m, c) -> DeterministicEnricher.tags(m, DeterministicEnricher.TagStrategy.SECTION_SCOPED, c.title, c.text));
        System.out.printf("%nTAG METHODS on %d postings, %d expected tag links%n", cases.size(), naive.tp + naive.fn);
        for (var e : java.util.Map.of("1 naive substring", naive, "2 guarded vocabulary, whole text", full, "3 guarded vocabulary, requirement sections", scoped).entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            Score s = e.getValue();
            System.out.printf("  %-46s tp=%2d fp=%2d fn=%2d precision=%.2f recall=%.2f f1=%.2f%n", e.getKey(), s.tp, s.fp, s.fn, s.precision(), s.recall(), s.f1());
        }
        for (Case c : cases) {
            VocabularyTagMatcher m = new VocabularyTagMatcher(vocabulary);
            Set<Long> fullGot = DeterministicEnricher.tags(m, DeterministicEnricher.TagStrategy.FULL_TEXT, c.title, c.text);
            if (!fullGot.equals(c.tags)) System.out.println("  whole-text miss " + c.id + ": want " + names(c.tags) + " got " + names(fullGot));
            Set<Long> got = DeterministicEnricher.tags(m, DeterministicEnricher.TagStrategy.SECTION_SCOPED, c.title, c.text);
            if (!got.equals(c.tags)) System.out.println("  scoped miss " + c.id + ": want " + names(c.tags) + " got " + names(got));
        }
        // The chosen method (whole text, guarded) must stay well ahead of the baseline; these floors fail if the guards break.
        assertThat(naive.precision()).isLessThan(0.7);
        assertThat(full.precision()).isGreaterThanOrEqualTo(0.95);
        assertThat(full.recall()).isGreaterThanOrEqualTo(0.95);
        assertThat(scoped.precision()).isGreaterThanOrEqualTo(0.95);
        assertThat(scoped.recall()).isLessThan(full.recall()); // scoping to sections loses tags named outside a heading
    }

    @Test
    void fieldRulesCompared() {
        int sections = 0, sectionsOk = 0, seasonOk = 0, seasonWrong = 0, cyOk = 0, cyWrong = 0, authOk = 0, authWrong = 0, seasonWant = 0, cyWant = 0, authWant = 0;
        for (Case c : cases) {
            Enrichment e = DeterministicEnricher.fields(c.title, c.text);
            if (c.hasBasic != null) {
                sections += 2;
                if ((e.basicRequirements() != null) == c.hasBasic) sectionsOk++; else System.out.println("  basic mismatch " + c.id);
                if ((e.preferredRequirements() != null) == c.hasPreferred) sectionsOk++; else System.out.println("  preferred mismatch " + c.id);
            }
            if (c.season != null) seasonWant++;
            if (c.classYear != null) cyWant++;
            if (c.auth != null) authWant++;
            if (java.util.Objects.equals(e.season(), c.season)) { if (c.season != null) seasonOk++; } else { seasonWrong++; System.out.println("  season " + c.id + ": " + e.season()); }
            if (java.util.Objects.equals(e.classYear(), c.classYear)) { if (c.classYear != null) cyOk++; } else { cyWrong++; System.out.println("  classYear " + c.id + ": " + e.classYear()); }
            if (java.util.Objects.equals(e.workAuthorization(), c.auth)) { if (c.auth != null) authOk++; } else { authWrong++; System.out.println("  auth " + c.id + ": " + e.workAuthorization()); }
        }
        System.out.printf("%nFIELD RULES: requirement-section presence %d/%d; season %d/%d (wrong or spurious %d); class year %d/%d (%d); work authorization %d/%d (%d)%n",
                sectionsOk, sections, seasonOk, seasonWant, seasonWrong, cyOk, cyWant, cyWrong, authOk, authWant, authWrong);
        assertThat(sectionsOk).isEqualTo(sections);
        assertThat(seasonWrong + cyWrong + authWrong).isZero();
    }
}
