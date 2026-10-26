package app.resume.jobs.enrich.ml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.Span;

/**
 * The trained skill-span tagger (Apache OpenNLP maximum-entropy NameFinder, one entity type "skill"). Inference is
 * deterministic given the model file: no randomness, no network, no state kept between postings.
 */
public final class SkillSpanModel {
    public static final String RESOURCE = "/ml/skill-spans.bin";
    private static volatile SkillSpanModel bundled;

    private final TokenNameFinderModel model;

    public SkillSpanModel(TokenNameFinderModel model) {
        this.model = model;
    }

    public static SkillSpanModel bundled() {
        SkillSpanModel m = bundled;
        if (m == null) {
            synchronized (SkillSpanModel.class) {
                if (bundled == null) {
                    try (InputStream in = SkillSpanModel.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new IllegalStateException("model " + RESOURCE + " is missing");
                        bundled = new SkillSpanModel(new TokenNameFinderModel(in));
                    } catch (IOException e) {
                        throw new IllegalStateException("model " + RESOURCE + " is unreadable", e);
                    }
                }
                m = bundled;
            }
        }
        return m;
    }

    /** The skill-span surface forms in the posting text, in order, duplicates kept. Bounded by {@link SkillTokenizer}. */
    public List<String> spans(String text) {
        List<String> out = new ArrayList<>();
        NameFinderME finder = new NameFinderME(model); // one per call: the finder keeps adaptive state
        for (String[] sentence : SkillTokenizer.sentences(text)) {
            for (Span s : finder.find(sentence)) {
                out.add(String.join(" ", java.util.Arrays.copyOfRange(sentence, s.getStart(), s.getEnd())));
            }
        }
        finder.clearAdaptiveData();
        return out;
    }
}
