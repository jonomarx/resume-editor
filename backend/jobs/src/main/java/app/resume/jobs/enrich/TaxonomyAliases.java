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
 * Extra aliases for vocabulary tags from an open skills taxonomy (contender B). The data file is derived from O*NET (CC BY 4.0,
 * see taxonomy/NOTICE.md) by tools/taxonomy/derive_onet_acronyms.py: acronym to spelled-out forms ("SQL" to "Structured query
 * language"). A tag gets the expansions of an acronym only when the tag's own name or alias is that acronym, so no tags are
 * ever created. The file is untrusted: size, entry count and string lengths are capped and anything malformed is skipped.
 */
public final class TaxonomyAliases {
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 2_000;
    static final int MAX_STRING = 80;
    private static final String RESOURCE = "/taxonomy/onet-acronyms.json";
    private static volatile TaxonomyAliases shared;

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

    /** The bundled O*NET-derived data, loaded once. An unreadable file means no taxonomy aliases, never a failure. */
    public static TaxonomyAliases shared() {
        TaxonomyAliases s = shared;
        if (s == null) {
            synchronized (TaxonomyAliases.class) {
                if (shared == null) shared = loadBundled();
                s = shared;
            }
        }
        return s;
    }

    private static TaxonomyAliases loadBundled() {
        try (InputStream in = TaxonomyAliases.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new TaxonomyAliases(Map.of());
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) return new TaxonomyAliases(Map.of());
            JsonNode acr = new ObjectMapper().readTree(bytes).path("acronyms");
            Map<String, List<String>> m = new LinkedHashMap<>();
            acr.fields().forEachRemaining(e -> {
                List<String> l = new ArrayList<>();
                if (e.getValue().isArray()) e.getValue().forEach(x -> { if (x.isTextual()) l.add(x.asText()); });
                m.put(e.getKey(), l);
            });
            return new TaxonomyAliases(m);
        } catch (IOException | RuntimeException e) {
            return new TaxonomyAliases(Map.of());
        }
    }

    /** Spelled-out forms to add as aliases of this tag (empty when its name and aliases are not a known acronym). */
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
