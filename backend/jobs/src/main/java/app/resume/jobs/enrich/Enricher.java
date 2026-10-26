package app.resume.jobs.enrich;

/**
 * Step 6 of the daily run: tag a posting from the shared vocabulary and fill requirements and eligibility fields.
 * Implementations are chosen by the ENRICHER environment variable (deterministic or llm); callers depend on this
 * interface only.
 */
public interface Enricher {
    record Result(int tagsLinked, int proposalsCreated) {}

    /** Enriches the stored posting. A posting whose enrichment fails stays as it was. */
    Result enrich(long postingId);
}
