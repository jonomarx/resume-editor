package app.resume.vocabulary;

import java.util.List;
import java.util.Optional;

/**
 * Read-only access to a user's shared tag vocabulary, for modules that must not depend on core (for example the Job
 * List Manager validating and proposing tags). Every method is scoped to the owner user: the vocabulary is per user, and
 * another user's tags are never visible. Implemented by core on top of the vocabulary code behind `/api/tags`; this port
 * never creates or changes tags.
 *
 * <p>Names are resolved exactly like the tag API's get-or-create: the trimmed, lower-cased name is compared with the
 * alias keys, then the computed slug (trim, NFKC, lowercase, `+` becomes `-plus-`, `#` becomes `-sharp-`, other runs
 * become one hyphen) is compared with the slugs and the alias keys.
 */
public interface TagVocabulary {
    /** The tag id for a name, slug or alias (case-insensitive, normalized like the vocabulary), if it exists. */
    Optional<Long> resolve(long userId, String name);

    /** The tag a name, slug or alias resolves to, if it exists. A null or blank name resolves to nothing. */
    Optional<VocabularyTag> find(long userId, String nameOrAlias);

    /** The tag with this canonical slug (an alias is not a slug), if it exists. */
    Optional<VocabularyTag> findBySlug(long userId, String slug);

    /** The user's tags ordered by name, optionally only one category (null for all). */
    List<VocabularyTag> list(long userId, VocabularyCategory category);
}
