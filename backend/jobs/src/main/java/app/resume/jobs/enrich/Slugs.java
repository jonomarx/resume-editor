package app.resume.jobs.enrich;

import java.util.Locale;

/** Same canonical slug rule as the vocabulary (DESIGN section 6): lowercase letters, digits, hyphens; + is plus, # is sharp. */
public final class Slugs {
    private Slugs() {}

    public static String slug(String name) {
        String s = name.strip().toLowerCase(Locale.ROOT).replace("+", " plus ").replace("#", " sharp ");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s;
    }
}
