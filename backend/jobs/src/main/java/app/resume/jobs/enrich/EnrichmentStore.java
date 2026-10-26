package app.resume.jobs.enrich;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists an enrichment: one transaction replaces the posting's fields and tags, and records tag proposals. */
final class EnrichmentStore {
    record Posting(long owner, String title, String company, String location, String description) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    EnrichmentStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    Posting load(long postingId) {
        return jdbc.queryForObject("SELECT owner_user_id, title, company, location, description FROM job_posting WHERE id = ?",
                (rs, i) -> new Posting(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), postingId);
    }

    /** @return the number of proposals newly created */
    int save(long postingId, long owner, Set<Long> tagIds, Enrichment e, List<Enrichment.TagCandidate> unknown,
            java.util.function.Predicate<Enrichment.TagCandidate> nowKnown) {
        int[] proposals = {0};
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE job_posting SET basic_requirements = ?, preferred_requirements = ?, season = ?,"
                    + " work_authorization = ?, class_year = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    e.basicRequirements(), e.preferredRequirements(), e.season(), e.workAuthorization(), e.classYear(), postingId);
            jdbc.update("DELETE FROM job_posting_tag WHERE posting_id = ?", postingId);
            for (long tagId : tagIds) {
                jdbc.update("INSERT INTO job_posting_tag (posting_id, tag_id) VALUES (?, ?)", postingId, tagId);
            }
            for (Enrichment.TagCandidate c : unknown) {
                String slug = Slugs.slug(c.name());
                // Unique (owner, slug) is the guard: a concurrent or repeated proposal is a no-op.
                proposals[0] += jdbc.update("INSERT INTO job_tag_proposal (owner_user_id, name, slug, category, first_posting_id)"
                        + " SELECT ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM job_tag_proposal WHERE owner_user_id = ? AND slug = ?)",
                        owner, c.name(), slug, c.category(), postingId, owner, slug);
            }
            // A proposal whose tag has since entered the vocabulary is settled.
            for (Enrichment.TagCandidate c : e.tags()) {
                if (nowKnown.test(c)) {
                    jdbc.update("UPDATE job_tag_proposal SET status = 'approved' WHERE owner_user_id = ? AND slug = ? AND status = 'pending'",
                            owner, Slugs.slug(c.name()));
                }
            }
        });
        return proposals[0];
    }
}
