package app.resume.jobs;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure functions that turn raw posting fields into the values stored on job_posting: a canonical URL (tracking
 * parameters stripped), its hash, a content hash, and a secondary dedupe key (company + title + location).
 */
public final class PostingNormalizer {
    private PostingNormalizer() {}

    /**
     * Query parameters dropped by the contract (openapi.yaml, `createSource`): exactly utm_*, gclid, fbclid, mc_cid,
     * mc_eid, ref and ref_src. Nothing else is rewritten, because other parameters can identify a posting.
     */
    private static final Set<String> TRACKING_PARAMS = Set.of("gclid", "fbclid", "mc_cid", "mc_eid", "ref", "ref_src");
    private static final List<String> TRACKING_PREFIXES = List.of("utm_");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Canonical form (the contract's normalization): http(s) only; lower-case scheme and host; default port and
     * fragment dropped; tracking parameters dropped; remaining parameters sorted by name; an empty path becomes
     * {@code /} and a trailing slash is removed from any other path; percent-encoding hex digits upper-cased.
     *
     * @throws IllegalArgumentException if the text is not an absolute http(s) URL
     */
    public static String canonicalUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("url is empty");
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid url: " + raw, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("not an absolute http(s) url: " + raw);
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) path = "/";
        StringBuilder out = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) out.append(':').append(port);
        out.append(upperPercent(path));
        List<String> kept = new ArrayList<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isEmpty()) continue;
                String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                if (!isTracking(name)) kept.add(pair);
            }
        }
        kept.replaceAll(PostingNormalizer::upperPercent);
        // Sorted by name, then by whole pair so equal names keep a stable order.
        kept.sort(java.util.Comparator.comparing((String pair) -> pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair)
                .thenComparing(java.util.Comparator.naturalOrder()));
        if (!kept.isEmpty()) out.append('?').append(String.join("&", kept));
        return out.toString();
    }

    private static String upperPercent(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                b.append(Character.toUpperCase(s.charAt(i + 1))).append(Character.toUpperCase(s.charAt(i + 2)));
                i += 2;
            }
        }
        return b.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    private static boolean isTracking(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (TRACKING_PARAMS.contains(n)) return true;
        return TRACKING_PREFIXES.stream().anyMatch(n::startsWith);
    }

    /** SHA-256 hex of the canonical URL. */
    public static String urlHash(String canonicalUrl) {
        return sha256(canonicalUrl);
    }

    /**
     * SHA-256 hex over the fields whose change means the posting changed (whitespace and case-insensitive on text
     * fields, so reflowed HTML does not look like an edit).
     */
    public static String contentHash(String title, String company, String location, String description) {
        return sha256(String.join("\u0001", squash(title), squash(company), squash(location), squash(description)));
    }

    /**
     * Secondary dedupe key: the same role found at two URLs. Lower-cased, accents folded, punctuation and whitespace
     * collapsed, so "Software Engineer Intern - Summer 2027 " and "software engineer intern summer 2027" match.
     */
    public static String dedupeKey(String company, String title, String location) {
        return sha256(String.join("\u0001", fold(company), fold(title), fold(location)));
    }

    private static String squash(String s) {
        return s == null ? "" : WHITESPACE.matcher(s.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    private static String fold(String s) {
        if (s == null) return "";
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return NON_ALNUM.matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
