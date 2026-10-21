package app.resume.jobs.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Tier 1: reads schema.org/JobPosting objects out of {@code <script type="application/ld+json">} blocks. Handles a
 * single object, arrays, {@code @graph} and ItemList wrappers. Everything it reads is untrusted text: it is only
 * copied into fields, never interpreted.
 */
public class JsonLdPostingAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<ExtractedPosting> extract(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        List<ExtractedPosting> out = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                collect(MAPPER.readTree(script.data()), baseUrl, out);
            } catch (Exception malformed) {
                // One broken block must not hide the others on the page.
            }
        }
        return out;
    }

    private void collect(JsonNode node, String baseUrl, List<ExtractedPosting> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(n -> collect(n, baseUrl, out));
            return;
        }
        if (!node.isObject()) return;
        if (isType(node, "JobPosting")) {
            toPosting(node, baseUrl).ifPresent(out::add);
        }
        collect(node.get("@graph"), baseUrl, out);
        if (isType(node, "ItemList")) {
            JsonNode items = node.get("itemListElement");
            if (items != null && items.isArray()) {
                for (JsonNode item : items) collect(item.has("item") ? item.get("item") : item, baseUrl, out);
            }
        }
    }

    private static boolean isType(JsonNode node, String type) {
        JsonNode t = node.get("@type");
        if (t == null) return false;
        if (t.isArray()) {
            for (JsonNode n : t) if (type.equalsIgnoreCase(n.asText())) return true;
            return false;
        }
        return type.equalsIgnoreCase(t.asText());
    }

    private java.util.Optional<ExtractedPosting> toPosting(JsonNode n, String baseUrl) {
        String title = text(n.get("title"));
        if (title == null) title = text(n.get("name"));
        if (title == null) return java.util.Optional.empty();
        String url = text(n.get("url"));
        if (url == null) {
            JsonNode id = n.get("@id");
            url = id == null ? null : text(id);
        }
        if (url != null) {
            try {
                url = java.net.URI.create(baseUrl).resolve(url).toString();
            } catch (IllegalArgumentException e) {
                url = null;
            }
        }
        JsonNode employment = n.get("employmentType");
        String type = employment != null && employment.isArray()
                ? String.join(",", stream(employment)) : text(employment);
        return java.util.Optional.of(new ExtractedPosting(title, organization(n.get("hiringOrganization")),
                location(n), htmlToText(text(n.get("description"))), url, text(n.get("datePosted")), type));
    }

    private static List<String> stream(JsonNode array) {
        List<String> l = new ArrayList<>();
        array.forEach(x -> {
            String t = text(x);
            if (t != null) l.add(t);
        });
        return l;
    }

    private static String organization(JsonNode org) {
        if (org == null) return null;
        if (org.isObject()) return text(org.get("name"));
        return text(org);
    }

    private static String location(JsonNode n) {
        List<String> parts = new ArrayList<>();
        JsonNode loc = n.get("jobLocation");
        List<JsonNode> locs = new ArrayList<>();
        if (loc != null) {
            if (loc.isArray()) loc.forEach(locs::add);
            else locs.add(loc);
        }
        for (JsonNode l : locs) {
            JsonNode a = l.get("address");
            if (a == null) {
                String t = text(l.get("name"));
                if (t != null) parts.add(t);
            } else if (a.isTextual()) {
                parts.add(a.asText().strip());
            } else {
                List<String> bits = new ArrayList<>();
                for (String f : new String[] {"addressLocality", "addressRegion", "addressCountry"}) {
                    String t = text(a.get(f));
                    if (t != null) bits.add(t);
                }
                if (!bits.isEmpty()) parts.add(String.join(", ", bits));
            }
        }
        if ("TELECOMMUTE".equalsIgnoreCase(text(n.get("jobLocationType")))) parts.add("Remote");
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) return n.has("name") ? text(n.get("name")) : null;
        String s = n.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static String htmlToText(String html) {
        if (html == null) return null;
        // Many sites ship the description as entity-escaped HTML ("&lt;p&gt;..."); unescape it once before parsing.
        if (html.contains("&lt;")) html = org.jsoup.parser.Parser.unescapeEntities(html, false);
        Document d = Jsoup.parse(html);
        d.select("br").after("\\n");
        d.select("p,li,div,h1,h2,h3,h4").after("\\n");
        String t = d.text().replace("\\n", "\n").replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return t.isEmpty() ? null : t;
    }
}
