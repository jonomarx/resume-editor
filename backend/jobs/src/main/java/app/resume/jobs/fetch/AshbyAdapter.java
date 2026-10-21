package app.resume.jobs.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ashby public job board API: {@code GET api.ashbyhq.com/posting-api/job-board/{board}}. */
public class AshbyAdapter implements AtsFeedAdapter {
    private static final List<String> HOSTS = List.of("jobs.ashbyhq.com", "api.ashbyhq.com");

    @Override
    public Optional<String> feedUrl(String sourceUrl) {
        String slug = AtsSupport.slug(sourceUrl, HOSTS);
        if (slug == null || slug.equals("posting-api")) return Optional.empty();
        return Optional.of("https://api.ashbyhq.com/posting-api/job-board/" + slug);
    }

    @Override
    public List<ExtractedPosting> parse(String feedBody, String sourceUrl) {
        JsonNode jobs = AtsSupport.readTree(feedBody).get("jobs");
        List<ExtractedPosting> out = new ArrayList<>();
        if (jobs == null || !jobs.isArray()) return out;
        String company = AtsSupport.slug(sourceUrl, HOSTS);
        for (JsonNode j : jobs) {
            String title = AtsSupport.text(j, "title");
            if (title == null) continue;
            if (j.has("isListed") && !j.get("isListed").asBoolean(true)) continue; // unlisted: not on the public board
            List<String> locations = new ArrayList<>();
            locations.add(AtsSupport.text(j, "location"));
            JsonNode secondary = j.get("secondaryLocations");
            if (secondary != null && secondary.isArray()) secondary.forEach(s -> locations.add(AtsSupport.text(s, "location")));
            String description = AtsSupport.text(j, "descriptionPlain");
            if (description == null) description = AtsSupport.htmlToText(AtsSupport.text(j, "descriptionHtml"));
            out.add(new ExtractedPosting(title, company, AtsSupport.join(locations, "; "), description,
                    AtsSupport.text(j, "jobUrl"), AtsSupport.text(j, "publishedAt"), AtsSupport.text(j, "employmentType")));
        }
        return out;
    }
}
