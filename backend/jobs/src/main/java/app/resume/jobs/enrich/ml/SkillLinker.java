package app.resume.jobs.enrich.ml;

import app.resume.vocabulary.VocabularyTag;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Links a skill span found by the model to a tag the user already has: exact match on the name or an alias (case-insensitive,
 * symbols kept), else a close spelling ("Postgres" for "PostgreSQL" is not close, "Kuberntes" for "Kubernetes" is). A span that
 * links to nothing is unknown and is never stored as a tag.
 */
public final class SkillLinker {
    static final int MIN_FUZZY_LENGTH = 6;
    static final double MIN_SIMILARITY = 0.85;

    private final Map<String, Long> exact = new HashMap<>();
    private final List<Map.Entry<String, Long>> fuzzy = new java.util.ArrayList<>();

    public SkillLinker(List<VocabularyTag> vocabulary) {
        for (VocabularyTag t : vocabulary) {
            put(t.id(), t.name());
            for (String a : t.aliases()) put(t.id(), a);
        }
    }

    private void put(long id, String name) {
        if (name == null || name.isBlank()) return;
        String key = key(name);
        if (exact.putIfAbsent(key, id) == null && key.length() >= MIN_FUZZY_LENGTH) fuzzy.add(Map.entry(key, id));
    }

    static String key(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public Optional<Long> link(String span) {
        if (span == null || span.isBlank() || span.length() > 60) return Optional.empty();
        String k = key(span);
        Long id = exact.get(k);
        if (id != null) return Optional.of(id);
        if (k.length() < MIN_FUZZY_LENGTH) return Optional.empty();
        Long best = null;
        double bestSim = MIN_SIMILARITY;
        for (var e : fuzzy) {
            if (Math.abs(e.getKey().length() - k.length()) > 2 || e.getKey().charAt(0) != k.charAt(0)) continue;
            double sim = 1.0 - (double) distance(e.getKey(), k) / Math.max(e.getKey().length(), k.length());
            if (sim >= bestSim) {
                bestSim = sim;
                best = e.getValue();
            }
        }
        return Optional.ofNullable(best);
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
