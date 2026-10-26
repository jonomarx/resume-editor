package app.resume.jobs.enrich;

import app.resume.vocabulary.TagVocabulary;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Enrichment without an LLM (ENRICHER=deterministic): vocabulary matching for tags, heading heuristics for requirements,
 * pattern rules for eligibility. Tags come only from the shared vocabulary, so there are never proposals; a field the text
 * does not state stays empty. The posting text is untrusted and only ever read by bounded regular expressions.
 */
public class DeterministicEnricher implements Enricher {
    /** How tags are found; see docs/decision-003-deterministic-enrichment.md for the measured comparison. */
    public enum TagStrategy {
        /** Guarded vocabulary match over the whole text. */
        FULL_TEXT,
        /** Guarded match over the title and requirement sections only; the whole text when the posting has no such section. */
        SECTION_SCOPED,
        /** Contender B: FULL_TEXT plus aliases from Wikidata and GitHub Linguist. */
        TAXONOMY,
        /** B with O*NET acronym expansions added as a secondary source. */
        TAXONOMY_ONET,
        /** Contender C: a small trained skill-span tagger (Apache OpenNLP), spans linked to the vocabulary. */
        ML
    }

    private final EnrichmentStore store;
    private final TagVocabulary vocabulary;
    private final TagStrategy strategy;

    public DeterministicEnricher(JdbcTemplate jdbc, TransactionTemplate tx, TagVocabulary vocabulary, TagStrategy strategy) {
        this.store = new EnrichmentStore(jdbc, tx);
        this.vocabulary = vocabulary;
        this.strategy = strategy;
    }

    @Override
    public Result enrich(long postingId) {
        EnrichmentStore.Posting p = store.load(postingId);
        var tags = vocabulary.list(p.owner(), null);
        Set<Long> tagIds = strategy == TagStrategy.ML ? mlTags(tags, p.title(), p.description())
                : tags(matcher(tags, strategy), strategy, p.title(), p.description());
        Enrichment e = fields(p.title(), p.description());
        store.save(postingId, p.owner(), tagIds, e, List.of(), c -> false);
        return new Result(tagIds.size(), 0);
    }

    /** Contender C: tags linked from the model's skill spans; unknown spans are dropped here (never stored as tags). */
    static Set<Long> mlTags(List<app.resume.vocabulary.VocabularyTag> tags, String title, String description) {
        return new app.resume.jobs.enrich.ml.MlTagger(app.resume.jobs.enrich.ml.SkillSpanModel.bundled(), tags).tag(title, description).tags();
    }

    static VocabularyTagMatcher matcher(List<app.resume.vocabulary.VocabularyTag> tags, TagStrategy strategy) {
        return new VocabularyTagMatcher(tags, switch (strategy) {
            case TAXONOMY -> TaxonomyAliases.shared(TaxonomyAliases.PRIMARY_SOURCES);
            case TAXONOMY_ONET -> TaxonomyAliases.shared(TaxonomyAliases.ALL_SOURCES);
            default -> null;
        });
    }

    /** Tags for a posting under a strategy (also used by the comparison tests). */
    static Set<Long> tags(VocabularyTagMatcher matcher, TagStrategy strategy, String title, String description) {
        String head = title == null ? "" : title;
        String body = PostingText.normalize(description);
        RequirementSections.Sections sections = RequirementSections.parse(body);
        String scoped = RequirementSections.scoped(sections);
        String skills = RequirementSections.skillLists(body);
        boolean structured = !scoped.isBlank() || !skills.isBlank();
        String whole = head + "\n" + body;
        String zone = head + "\n" + scoped + "\n" + skills;
        if (strategy == TagStrategy.SECTION_SCOPED) {
            return structured ? matcher.match(zone, zone, VocabularyTagMatcher.Mode.GUARDED) : matcher.match(whole, VocabularyTagMatcher.Mode.GUARDED);
        }
        // Unambiguous names count anywhere; ambiguous ones (Go, R, C, Spring...) only in the title, requirement sections and
        // skills lists. A posting with no such structure at all (a short paste) uses its whole text as the zone.
        return matcher.match(whole, structured ? zone : whole, VocabularyTagMatcher.Mode.GUARDED);
    }

    /** Requirements and eligibility fields, no tags. */
    static Enrichment fields(String title, String description) {
        String body = PostingText.normalize(description);
        RequirementSections.Sections s = RequirementSections.parse(body);
        String season = EligibilityRules.season(title);
        if (season == null) season = EligibilityRules.season(body);
        return new Enrichment(List.of(), s.basic(), s.preferred(), season, EligibilityRules.workAuthorization(body),
                EligibilityRules.classYear(body));
    }
}
