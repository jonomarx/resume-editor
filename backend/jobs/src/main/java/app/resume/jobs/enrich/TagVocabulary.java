package app.resume.jobs.enrich;

import java.util.Optional;

/**
 * Read access to the shared tag vocabulary (core's `tag` and `tag_key`). DUMMY(US-020): the vocabulary lives in core
 * and no interface for it exists in `common` yet, so jobs codes against this local port; the real adapter needs a
 * contract story that puts the port in `common`. Until then {@link DummyTagVocabulary} stands in.
 */
public interface TagVocabulary {
    /** The tag id for a name, slug or alias (case-insensitive, normalized like the vocabulary), if it exists. */
    Optional<Long> resolve(long userId, String name);
}
