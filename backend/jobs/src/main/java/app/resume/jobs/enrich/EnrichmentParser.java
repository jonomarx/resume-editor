package app.resume.jobs.enrich;

import app.resume.jobs.extract.ExtractionOutputException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Strict validation of the model's enrichment output. Any violation rejects the whole output. */
final class EnrichmentParser {
    static final int MAX_TAGS = 30;
    static final Set<String> CATEGORIES = Set.of("language", "framework", "domain", "role_type");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnrichmentParser() {}

    static Enrichment parse(String raw) {
        if (raw == null || raw.isBlank()) throw new ExtractionOutputException("empty output");
        JsonNode root;
        try {
            String s = raw.strip();
            if (s.startsWith("```") && s.lastIndexOf("```") > s.indexOf('\n') && s.indexOf('\n') > 0) {
                s = s.substring(s.indexOf('\n') + 1, s.lastIndexOf("```")).strip();
            }
            root = MAPPER.readTree(s);
        } catch (Exception e) {
            throw new ExtractionOutputException("output is not JSON", e);
        }
        if (root == null || !root.isObject()) throw new ExtractionOutputException("output is not an object");
        JsonNode tags = root.get("tags");
        if (tags == null || !tags.isArray()) throw new ExtractionOutputException("tags must be an array");
        if (tags.size() > MAX_TAGS) throw new ExtractionOutputException("too many tags");
        List<Enrichment.TagCandidate> out = new ArrayList<>();
        for (JsonNode t : tags) {
            if (!t.isObject()) throw new ExtractionOutputException("tag is not an object");
            String name = str(t, "name", 60, true);
            String category = str(t, "category", 20, true);
            if (!CATEGORIES.contains(category)) throw new ExtractionOutputException("unknown category: " + category);
            if (Slugs.slug(name).isEmpty()) throw new ExtractionOutputException("tag name has no letters or digits");
            out.add(new Enrichment.TagCandidate(name, category));
        }
        return new Enrichment(out, str(root, "basicRequirements", 5000, false), str(root, "preferredRequirements", 5000, false),
                str(root, "season", 40, false), str(root, "workAuthorization", 120, false), str(root, "classYear", 40, false));
    }

    private static String str(JsonNode obj, String field, int max, boolean required) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) {
            if (required) throw new ExtractionOutputException("missing " + field);
            return null;
        }
        if (!n.isTextual()) throw new ExtractionOutputException(field + " is not a string");
        String s = n.asText().strip();
        if (s.isEmpty()) {
            if (required) throw new ExtractionOutputException("empty " + field);
            return null;
        }
        if (s.length() > max) throw new ExtractionOutputException(field + " is too long");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\t') throw new ExtractionOutputException(field + " has control characters");
        }
        return s;
    }
}
