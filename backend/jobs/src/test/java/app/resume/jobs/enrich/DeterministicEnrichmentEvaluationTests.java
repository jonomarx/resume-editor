package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * The method comparison (docs/decision-003-deterministic-enrichment.md): precision and recall of each tag method on the
 * labelled fixture sets, and exact-match accuracy of the field rules. The printed tables are the evidence on the card.
 * The assertions pin floors so a regression in the chosen method fails the build.
 */
class DeterministicEnrichmentEvaluationTests {
    record Case(String id, String title, String text, Set<Long> tags, String season, String classYear, String auth, Boolean hasBasic,
            Boolean hasPreferred) {}

    record Score(int tp, int fp, int fn) {
        double precision() { return tp + fp == 0 ? 1 : (double) tp / (tp + fp); }
        double recall() { return tp + fn == 0 ? 1 : (double) tp / (tp + fn); }
        double f1() { double p = precision(), r = recall(); return p + r == 0 ? 0 : 2 * p * r / (p + r); }
        Score plus(Score o) { return new Score(tp + o.tp, fp + o.fp, fn + o.fn); }
    }

    /** One labelled fixture file: its vocabulary and postings. */
    static final class Dataset {
        final String name;
        final List<VocabularyTag> vocabulary = new ArrayList<>();
        final List<Case> cases = new ArrayList<>();
        final Map<String, Long> ids = new HashMap<>();

        Dataset(String file) throws Exception {
            this.name = file;
            JsonNode root = new ObjectMapper().readTree(DeterministicEnrichmentEvaluationTests.class.getResourceAsStream("/fixtures/enrichment/" + file));
            long next = 1;
            for (JsonNode v : root.get("vocabulary")) {
                List<String> aliases = new ArrayList<>();
                v.get("aliases").forEach(a -> aliases.add(a.asText()));
                String n = v.get("name").asText();
                vocabulary.add(new VocabularyTag(next, n, Slugs.slug(n), category(v.get("category").asText()), aliases));
                ids.put(n, next++);
            }
            for (JsonNode p : root.get("postings")) {
                Set<Long> tags = new HashSet<>();
                p.get("tags").forEach(t -> tags.add(ids.get(t.asText())));
                cases.add(new Case(p.get("id").asText(), p.get("title").asText(), p.get("text").asText(), tags, str(p, "season"),
                        str(p, "classYear"), str(p, "workAuthorization"), bool(p, "hasBasic"), bool(p, "hasPreferred")));
            }
        }

        String names(Set<Long> idSet) {
            List<String> n = new ArrayList<>();
            ids.forEach((k, v) -> { if (idSet.contains(v)) n.add(k); });
            java.util.Collections.sort(n);
            return n.toString();
        }

        Score score(BiFunction<VocabularyTagMatcher, Case, Set<Long>> method) {
            VocabularyTagMatcher matcher = new VocabularyTagMatcher(vocabulary);
            Score total = new Score(0, 0, 0);
            for (Case c : cases) {
                Set<Long> got = method.apply(matcher, c);
                int tp = 0, fp = 0, fn = 0;
                for (Long g : got) if (c.tags.contains(g)) tp++; else fp++;
                for (Long w : c.tags) if (!got.contains(w)) fn++;
                total = total.plus(new Score(tp, fp, fn));
            }
            return total;
        }
    }

    static VocabularyCategory category(String v) {
        for (VocabularyCategory c : VocabularyCategory.values()) if (c.value().equals(v)) return c;
        throw new IllegalArgumentException(v);
    }

    static String str(JsonNode n, String f) { return n.get(f).isNull() ? null : n.get(f).asText(); }

    static Boolean bool(JsonNode n, String f) { return n.get(f).isNull() ? null : n.get(f).asBoolean(); }

    static void print(String set, String method, Score s) {
        System.out.printf("  %-14s %-44s tp=%3d fp=%3d fn=%3d precision=%.2f recall=%.2f f1=%.2f%n", set, method, s.tp, s.fp, s.fn, s.precision(), s.recall(), s.f1());
    }

    record Method(String label, BiFunction<Dataset, Case, Set<Long>> run, boolean taxonomy) {}

    static final List<String> SETS = List.of("labelled-postings.json", "labelled-postings-long.json", "labelled-postings-aliased.json");
    static final List<String> SET_NAMES = List.of("short(16)", "long(4)", "aliased(17)");

    static Set<Long> tags(Dataset d, Case c, DeterministicEnricher.TagStrategy strategy) {
        return DeterministicEnricher.tags(DeterministicEnricher.matcher(d.vocabulary, strategy), strategy, c.title, c.text);
    }

    static final List<Method> METHODS = List.of(
            new Method("naive substring", (d, c) -> new VocabularyTagMatcher(d.vocabulary).match(c.title + "\n" + c.text, VocabularyTagMatcher.Mode.NAIVE), false),
            new Method("A guarded rules (default)", (d, c) -> tags(d, c, DeterministicEnricher.TagStrategy.FULL_TEXT), false),
            new Method("A sections only", (d, c) -> tags(d, c, DeterministicEnricher.TagStrategy.SECTION_SCOPED), false),
            new Method("B A + Wikidata + Linguist aliases", (d, c) -> tags(d, c, DeterministicEnricher.TagStrategy.TAXONOMY), true),
            new Method("B+ B + O*NET expansions", (d, c) -> tags(d, c, DeterministicEnricher.TagStrategy.TAXONOMY_ONET), true));

    static Score score(Dataset d, Method m) {
        Score total = new Score(0, 0, 0);
        for (Case c : d.cases) {
            Set<Long> got = m.run.apply(d, c);
            int tp = 0, fp = 0, fn = 0;
            for (Long g : got) if (c.tags.contains(g)) tp++; else fp++;
            for (Long w : c.tags) if (!got.contains(w)) fn++;
            total = total.plus(new Score(tp, fp, fn));
        }
        return total;
    }

    @Test
    void tagMethodsCompared() throws Exception {
        System.out.println("\nTAG METHODS");
        Score[] both = new Score[METHODS.size()];
        java.util.Arrays.fill(both, new Score(0, 0, 0));
        Map<String, Score[]> bySet = new HashMap<>();
        for (int i = 0; i < SETS.size(); i++) {
            Dataset d = new Dataset(SETS.get(i));
            Score[] row = new Score[METHODS.size()];
            for (int k = 0; k < METHODS.size(); k++) {
                row[k] = score(d, METHODS.get(k));
                print(SET_NAMES.get(i), METHODS.get(k).label, row[k]);
                if (i < 2) both[k] = both[k].plus(row[k]);
            }
            bySet.put(SETS.get(i), row);
            for (Case c : d.cases) {
                for (int k : new int[] {1, 3, 4}) {
                    Set<Long> got = METHODS.get(k).run.apply(d, c);
                    Set<Long> base = METHODS.get(1).run.apply(d, c);
                    if (k == 1 && !got.equals(c.tags)) System.out.println("    A differs on " + c.id + ": want " + d.names(c.tags) + " got " + d.names(got));
                    if (k > 1 && !got.equals(base)) System.out.println("    " + METHODS.get(k).label.split(" ")[0] + " vs A on " + c.id + ": want " + d.names(c.tags) + " A " + d.names(base) + " got " + d.names(got));
                }
            }
        }
        for (int k = 0; k < METHODS.size(); k++) print("short+long", METHODS.get(k).label, both[k]);
        Score[] shortRow = bySet.get(SETS.get(0));
        Score[] longRow = bySet.get(SETS.get(1));
        assertThat(shortRow[0].precision()).isLessThan(0.7);
        assertThat(shortRow[1].precision()).isGreaterThanOrEqualTo(0.9);
        assertThat(shortRow[1].recall()).isGreaterThanOrEqualTo(0.9);
        assertThat(longRow[1].precision()).isGreaterThanOrEqualTo(0.7);
        assertThat(longRow[1].recall()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void fieldRulesCompared() throws Exception {
        for (String file : List.of("labelled-postings.json", "labelled-postings-long.json")) {
            Dataset d = new Dataset(file);
            int sections = 0, sectionsOk = 0, want = 0, ok = 0, wrong = 0;
            for (Case c : d.cases) {
                Enrichment e = DeterministicEnricher.fields(c.title, c.text);
                if (c.hasBasic != null) {
                    sections += 2;
                    if ((e.basicRequirements() != null) == c.hasBasic) sectionsOk++; else System.out.println("  basic mismatch " + c.id);
                    if ((e.preferredRequirements() != null) == c.hasPreferred) sectionsOk++; else System.out.println("  preferred mismatch " + c.id);
                }
                String[][] pairs = {{e.season(), c.season}, {e.classYear(), c.classYear}, {e.workAuthorization(), c.auth}};
                String[] names = {"season", "classYear", "auth"};
                for (int k = 0; k < 3; k++) {
                    if (pairs[k][1] != null) want++;
                    if (Objects.equals(pairs[k][0], pairs[k][1])) { if (pairs[k][1] != null) ok++; }
                    else { wrong++; System.out.println("  " + names[k] + " " + c.id + ": got " + pairs[k][0] + " want " + pairs[k][1]); }
                }
            }
            System.out.printf("FIELD RULES %s: requirement-section presence %d/%d; eligibility fields %d/%d correct, %d wrong or spurious%n", file, sectionsOk, sections, ok, want, wrong);
            assertThat(sectionsOk).isEqualTo(sections);
            assertThat(wrong).isZero();
        }
    }
}
