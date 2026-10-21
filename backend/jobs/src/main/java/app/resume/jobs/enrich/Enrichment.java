package app.resume.jobs.enrich;

import java.util.List;

/** What the model said about one posting, validated. Tag names are not yet checked against the vocabulary. */
public record Enrichment(List<TagCandidate> tags, String basicRequirements, String preferredRequirements, String season,
        String workAuthorization, String classYear) {
    public record TagCandidate(String name, String category) {}
}
