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
        /** Contender B: strategy FULL_TEXT plus aliases from the O*NET-derived taxonomy file. */
        TAXONOMY
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
        Set<Long> tagIds = tags(matcher(vocabulary.list(p.owner(), null), strategy), strategy, p.title(), p.description());
        Enrichment e = fields(p.title(), p.description());
        store.save(postingId, p.owner(), tagIds, e, List.of(), c -> false);
        return new Result(tagIds.size(), 0);
    }

    static VocabularyTagMatcher matcher(List<app.resume.vocabulary.VocabularyTag> tags, TagStrategy strategy) {
        return new VocabularyTagMatcher(tags, strategy == TagStrategy.TAXONOMY ? TaxonomyAliases.shared() : null);
    }

    /** Tags for a posting under a strategy (also used by the comparison tests). */
    static Set<Long> tags(VocabularyTagMatcher matcher, TagStrategy strategy, String title, String description) {
        String head = title == null ? "" : title;
        String body = description == null ? "" : description;
        if (strategy == TagStrategy.SECTION_SCOPED) {
            String scoped = RequirementSections.scoped(RequirementSections.parse(body));
            if (!scoped.isBlank()) return matcher.match(head + "\n" + scoped, VocabularyTagMatcher.Mode.GUARDED);
        }
        return matcher.match(head + "\n" + body, VocabularyTagMatcher.Mode.GUARDED);
    }

    /** Requirements and eligibility fields, no tags. */
    static Enrichment fields(String title, String description) {
        String body = description == null ? "" : description;
        if (body.length() > VocabularyTagMatcher.MAX_TEXT) body = body.substring(0, VocabularyTagMatcher.MAX_TEXT);
        RequirementSections.Sections s = RequirementSections.parse(body);
        String season = EligibilityRules.season(title);
        if (season == null) season = EligibilityRules.season(body);
        return new Enrichment(List.of(), s.basic(), s.preferred(), season, EligibilityRules.workAuthorization(body),
                EligibilityRules.classYear(body));
    }
}
