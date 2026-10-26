package app.resume.jobs.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lever Postings API: {@code GET api.lever.co/v0/postings/{company}?mode=json}. */
public class LeverAdapter implements AtsFeedAdapter {
    private static final List<String> HOSTS = List.of("jobs.lever.co", "jobs.eu.lever.co", "api.lever.co");

    @Override
    public Optional<String> feedUrl(String sourceUrl) {
        String slug = AtsSupport.slug(sourceUrl, HOSTS);
        if (slug == null || slug.equals("v0")) return Optional.empty();
        String host = sourceUrl.contains("eu.lever.co") ? "api.eu.lever.co" : "api.lever.co";
        return Optional.of("https://" + host + "/v0/postings/" + slug + "?mode=json");
    }

    @Override
    public List<ExtractedPosting> parse(String feedBody, String sourceUrl) {
        JsonNode root = AtsSupport.readTree(feedBody);
        List<ExtractedPosting> out = new ArrayList<>();
        if (!root.isArray()) return out;
        String company = AtsSupport.slug(sourceUrl, HOSTS);
        for (JsonNode j : root) {
            String title = AtsSupport.text(j, "text");
            if (title == null) continue;
            JsonNode cat = j.get("categories");
            List<String> locations = new ArrayList<>();
            locations.add(AtsSupport.text(cat, "location"));
            if (cat != null && cat.get("allLocations") != null && cat.get("allLocations").isArray()) {
                cat.get("allLocations").forEach(l -> locations.add(AtsSupport.text(l)));
            }
            String location = AtsSupport.join(locations, "; ");
            if ("remote".equalsIgnoreCase(AtsSupport.text(j, "workplaceType"))) {
                location = AtsSupport.join(java.util.Arrays.asList(location, "Remote"), "; ");
            }
            out.add(new ExtractedPosting(title, company, location, description(j), AtsSupport.text(j, "hostedUrl"),
                    AtsSupport.epochMillisToIso(j.get("createdAt")), AtsSupport.text(cat, "commitment")));
        }
        return out;
    }

    private static String description(JsonNode j) {
        List<String> parts = new ArrayList<>();
        parts.add(AtsSupport.text(j, "descriptionPlain"));
        JsonNode lists = j.get("lists");
        if (lists != null && lists.isArray()) {
            for (JsonNode l : lists) {
                parts.add(AtsSupport.text(l, "text"));
                parts.add(AtsSupport.htmlToText(AtsSupport.text(l, "content")));
            }
        }
        parts.add(AtsSupport.text(j, "additionalPlain"));
        return AtsSupport.join(parts, "\n\n");
    }
}
