package app.resume.tags;

import app.resume.vocabulary.TagVocabulary;
import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** The read-only {@link TagVocabulary} port, answered by {@link TagService}: no normalization or alias logic of its own. */
@Component
public class CoreTagVocabulary implements TagVocabulary {
    private final TagService tags;

    public CoreTagVocabulary(TagService tags) {
        this.tags = tags;
    }

    @Override
    public Optional<Long> resolve(long userId, String name) {
        return find(userId, name).map(VocabularyTag::id);
    }

    @Override
    public Optional<VocabularyTag> find(long userId, String nameOrAlias) {
        return tags.lookup(userId, nameOrAlias).map(CoreTagVocabulary::toView);
    }

    @Override
    public Optional<VocabularyTag> findBySlug(long userId, String slug) {
        if (slug == null || !TagNormalizer.isSlug(slug)) return Optional.empty();
        try {
            return Optional.of(toView(tags.requireBySlug(userId, slug)));
        } catch (TagNotFoundException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<VocabularyTag> list(long userId, VocabularyCategory category) {
        TagCategory c = category == null ? null : TagCategory.fromValue(category.value());
        return tags.list(userId, c, null).stream().map(CoreTagVocabulary::toView).toList();
    }

    private static VocabularyTag toView(Tag t) {
        return new VocabularyTag(t.id(), t.name(), t.slug(), VocabularyCategory.valueOf(t.category().name()), t.aliases());
    }
}
