package app.resume.jobs.enrich;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** DUMMY(US-020): in-memory vocabulary for the "dummy" profile; ids are made up and do not exist in the tag table. */
@Component
@Profile("dummy")
public class DummyTagVocabulary implements TagVocabulary {
    private final Map<String, Long> byKey = new ConcurrentHashMap<>();

    public DummyTagVocabulary add(String name, long id) {
        byKey.put(Slugs.slug(name), id);
        return this;
    }

    @Override
    public Optional<Long> resolve(long userId, String name) {
        return Optional.ofNullable(byKey.get(Slugs.slug(name)));
    }
}
