package app.resume.jobs.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Greenhouse Job Board API: {@code GET boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true}. */
public class GreenhouseAdapter implements AtsFeedAdapter {
    private static final List<String> HOSTS = List.of("boards.greenhouse.io", "job-boards.greenhouse.io",
            "boards-api.greenhouse.io", "job-boards.eu.greenhouse.io");

    @Override
    public Optional<String> feedUrl(String sourceUrl) {
        String slug = AtsSupport.slug(sourceUrl, HOSTS);
        // The API host's first segment is "v1", not a board; those URLs are already feeds and are not re-derived.
        if (slug == null || slug.equals("v1") || slug.equals("embed")) return Optional.empty();
        return Optional.of("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true");
    }

    @Override
    public List<ExtractedPosting> parse(String feedBody, String sourceUrl) {
        JsonNode jobs = AtsSupport.readTree(feedBody).get("jobs");
        List<ExtractedPosting> out = new ArrayList<>();
        if (jobs == null || !jobs.isArray()) return out;
        for (JsonNode j : jobs) {
            String title = AtsSupport.text(j, "title");
            if (title == null) continue;
            out.add(new ExtractedPosting(title, AtsSupport.text(j, "company_name"),
                    AtsSupport.text(j.get("location"), "name"), AtsSupport.htmlToText(AtsSupport.text(j, "content")),
                    AtsSupport.text(j, "absolute_url"),
                    AtsSupport.text(j, "first_published"), // updated_at is the last edit, not the publication date
                    null));
        }
        return out;
    }
}
