package app.resume.jobs.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

/** Small helpers shared by the ATS adapters. All feed text is untrusted and only copied into fields. */
final class AtsSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private AtsSupport() {}

    static JsonNode readTree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new FetchException("ATS feed is not valid JSON", e);
        }
    }

    /** First path segment of the source URL (the board or company slug) if the host matches, else null. */
    static String slug(String sourceUrl, List<String> hosts) {
        try {
            URI u = URI.create(sourceUrl.strip());
            if (u.getHost() == null || !hosts.contains(u.getHost().toLowerCase(Locale.ROOT))) return null;
            String path = u.getPath() == null ? "" : u.getPath();
            for (String seg : path.split("/")) if (!seg.isEmpty()) return seg.matches("[A-Za-z0-9_.-]+") ? seg : null;
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isContainerNode()) return null;
        String s = n.asText().strip();
        return s.isEmpty() ? null : s;
    }

    static String text(JsonNode n, String field) {
        return n == null ? null : text(n.get(field));
    }

    /** HTML (optionally entity-escaped, as Greenhouse ships it) to plain text with paragraph breaks. */
    static String htmlToText(String html) {
        if (html == null) return null;
        if (html.contains("&lt;")) html = Parser.unescapeEntities(html, false);
        Document d = Jsoup.parse(html);
        d.select("br").after("\\n");
        d.select("p,li,div,h1,h2,h3,h4,ul,ol").after("\\n");
        String t = d.text().replace("\\n", "\n").replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return t.isEmpty() ? null : t;
    }

    static String join(List<String> parts, String sep) {
        List<String> l = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank() && !l.contains(p)) l.add(p);
        return l.isEmpty() ? null : String.join(sep, l);
    }

    static String epochMillisToIso(JsonNode n) {
        return n != null && n.isNumber() ? Instant.ofEpochMilli(n.asLong()).toString() : null;
    }
}
