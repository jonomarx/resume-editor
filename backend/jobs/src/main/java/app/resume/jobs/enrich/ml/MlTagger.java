package app.resume.jobs.enrich.ml;

import app.resume.vocabulary.VocabularyTag;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Contender C: skill spans from the model, linked to the user's vocabulary; unknown spans are reported, never stored. */
public final class MlTagger {
    public record Result(Set<Long> tags, Set<String> unknownSpans) {}

    private final SkillSpanModel model;
    private final SkillLinker linker;

    public MlTagger(SkillSpanModel model, List<VocabularyTag> vocabulary) {
        this.model = model;
        this.linker = new SkillLinker(vocabulary, app.resume.jobs.enrich.TaxonomyAliases.shared(app.resume.jobs.enrich.TaxonomyAliases.ALL_SOURCES));
    }

    public Result tag(String title, String description) {
        Set<Long> tags = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        String text = (title == null ? "" : title) + "\n" + (description == null ? "" : description);
        for (String span : model.spans(text)) {
            linker.link(span).ifPresentOrElse(tags::add, () -> unknown.add(span));
        }
        return new Result(tags, unknown);
    }
}
