package app.resume.vocabulary;

import java.util.List;

/**
 * One vocabulary entry as other modules see it: immutable, no persistence types.
 *
 * @param slug canonical slug (lowercase letters, digits and hyphens); node tags and posting tags store slugs
 * @param aliases aliases as typed (printable ASCII, any case); the list is unmodifiable
 */
public record VocabularyTag(long id, String name, String slug, VocabularyCategory category, List<String> aliases) {
    public VocabularyTag {
        aliases = List.copyOf(aliases);
    }
}
