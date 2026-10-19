package app.resume.tags;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a name or alias into its slug: lower case, accents stripped, "+" and "#" spelled out (so "C++", "C#" and "C"
 * stay distinct), every other run of non-alphanumerics becomes one hyphen, no leading or trailing hyphen.
 * "Node.js" gives "node-js", "C++" gives "c-plus-plus", "  PostgreSQL " gives "postgresql".
 */
public final class TagNormalizer {
    public static final int MAX_SLUG_LENGTH = 80;

    private TagNormalizer() {}

    public static String slug(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replace("+", " plus ").replace("#", " sharp ");
        s = s.replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }
}
