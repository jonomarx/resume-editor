package app.resume.tags;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one normalization for tag names and aliases (contract 0.3.0, addTagAlias): trim, Unicode NFKC, lowercase,
 * every run of characters outside [a-z0-9] becomes one hyphen, leading and trailing hyphens are stripped. NFKC runs
 * before lowercasing (lowercasing first can leave uppercase letters, e.g. U+216B). Non-ASCII letters are not
 * transliterated: they act as separators ("café" gives "caf"). The output always matches {@link #SLUG}.
 */
public final class TagNormalizer {
    public static final int MAX_SLUG_LENGTH = 80;
    public static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private TagNormalizer() {}

    public static String slug(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    public static boolean isSlug(String s) {
        return s != null && s.length() <= MAX_SLUG_LENGTH && SLUG.matcher(s).matches();
    }
}
