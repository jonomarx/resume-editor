package app.resume.jobs.enrich;

import app.resume.vocabulary.VocabularyTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Extra aliases for vocabulary tags from open taxonomies (contender B). Each source is one small derived file under
 * resources/taxonomy (Wikidata CC0, GitHub Linguist MIT, optionally O*NET CC BY 4.0; see NOTICE.md and tools/taxonomy) mapping a
 * lower-cased name to aliases. A tag gets the aliases of a key only when the tag's own name or one of its aliases is that key,
 * so no tags are ever created. The files are untrusted: size, entry count and string lengths are capped and anything malformed
 * is skipped.
 */
public final class TaxonomyAliases {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 2_000;
    static final int MAX_STRING = 80;
    public static final List<String> PRIMARY_SOURCES = List.of("wikidata-aliases.json", "linguist-aliases.json");
    public static final List<String> ALL_SOURCES = List.of("wikidata-aliases.json", "linguist-aliases.json", "onet-aliases.json");
    private static final java.util.concurrent.ConcurrentHashMap<List<String>, TaxonomyAliases> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<String, List<String>> byAcronym; // key: lower-cased acronym

    public TaxonomyAliases(Map<String, List<String>> acronymToExpansions) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (var e : acronymToExpansions.entrySet()) {
            if (m.size() >= MAX_ENTRIES) break;
            String k = e.getKey();
            if (k == null || k.isBlank() || k.length() > MAX_STRING) continue;
            List<String> ok = new ArrayList<>();
            for (String x : e.getValue()) {
                if (x != null && !x.isBlank() && x.length() <= MAX_STRING) ok.add(x.strip());
            }
            if (!ok.isEmpty()) m.put(k.strip().toLowerCase(Locale.ROOT), List.copyOf(ok));
        }
        this.byAcronym = Map.copyOf(m);
    }

    /** The bundled data of these sources, loaded once. An unreadable file means no aliases from it, never a failure. */
    public static TaxonomyAliases shared(List<String> sources) {
        return CACHE.computeIfAbsent(sources, TaxonomyAliases::loadBundled);
    }

    private static TaxonomyAliases loadBundled(List<String> sources) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (String source : sources) {
            try (InputStream in = TaxonomyAliases.class.getResourceAsStream("/taxonomy/" + source)) {
                if (in == null) continue;
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) continue;
                new ObjectMapper().readTree(bytes).path("aliases").fields().forEachRemaining(e -> {
                    List<String> l = merged.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                    if (e.getValue().isArray()) e.getValue().forEach(x -> { if (x.isTextual() && !l.contains(x.asText())) l.add(x.asText()); });
                });
            } catch (IOException | RuntimeException e) {
                // skip this source
            }
        }
        return new TaxonomyAliases(merged);
    }

    /** Aliases to add to this tag (empty when its name and aliases are keys of no source). */
    public List<String> aliasesFor(VocabularyTag tag) {
        List<String> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add(tag.name());
        names.addAll(tag.aliases());
        for (String n : names) {
            if (n == null) continue;
            List<String> x = byAcronym.get(n.strip().toLowerCase(Locale.ROOT));
            if (x != null) for (String e : x) if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    int size() { return byAcronym.size(); }
}
