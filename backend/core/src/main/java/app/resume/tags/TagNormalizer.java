package app.resume.tags;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Slug normalization for tag names (contract 0.4.0): trim, Unicode NFKC, lowercase, "+" becomes "-plus-" and "#" becomes
 * "-sharp-", every run of characters outside [a-z0-9] becomes one hyphen, leading and trailing hyphens are stripped. NFKC
 * runs before lowercasing (lowercasing first can leave uppercase letters, e.g. U+216B). Non-ASCII letters are not
 * transliterated: they act as separators ("café" gives "caf"). The output always matches {@link #SLUG}. So "C", "C++" and
 * "C#" are `c`, `c-plus-plus` and `c-sharp`. Aliases are not slugs: see {@link #aliasKey} and {@link #isPrintableAscii}.
 */
public final class TagNormalizer {
    public static final int MAX_SLUG_LENGTH = 80;
    public static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private TagNormalizer() {}

    public static String slug(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = s.replace("+", "-plus-").replace("#", "-sharp-");
        s = s.replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /** The comparison key of an alias: trimmed and lower-cased (ASCII). */
    public static String aliasKey(String alias) {
        return alias.strip().toLowerCase(Locale.ROOT);
    }

    /** Printable ASCII only: space (0x20) to tilde (0x7E). */
    public static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    public static boolean isSlug(String s) {
        return s != null && s.length() <= MAX_SLUG_LENGTH && SLUG.matcher(s).matches();
    }
}
