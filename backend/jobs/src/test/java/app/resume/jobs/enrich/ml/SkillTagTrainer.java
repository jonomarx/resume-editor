package app.resume.jobs.enrich.ml;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.TrainingParameters;

/**
 * Trains the skill-span model from {@link SkillCorpus}. Reproducible: fixed corpus seed, maximum-entropy training (GIS) which
 * has no randomness. To regenerate the committed model:
 * {@code mvn -q test -pl backend/jobs -Dtest=SkillTagTrainer -Dml.write=backend/jobs/src/main/resources/ml/skill-spans.bin}
 * (a JUnit entry point below), then commit the file.
 */
public final class SkillTagTrainer {
    private SkillTagTrainer() {}

    public static TokenNameFinderModel train() throws IOException {
        List<NameSample> samples = SkillCorpus.samples();
        TrainingParameters params = new TrainingParameters();
        params.put(TrainingParameters.ALGORITHM_PARAM, "MAXENT");
        params.put(TrainingParameters.ITERATIONS_PARAM, 100);
        params.put(TrainingParameters.CUTOFF_PARAM, 3);
        params.put("Threads", 1);
        ObjectStream<NameSample> stream = new ObjectStream<>() {
            int i = 0;
            @Override public NameSample read() { return i < samples.size() ? samples.get(i++) : null; }
            @Override public void reset() { i = 0; }
            @Override public void close() {}
        };
        return NameFinderME.train("en", "skill", stream, params, new TokenNameFinderFactory());
    }

    public static void write(TokenNameFinderModel model, Path to) throws IOException {
        try (OutputStream out = Files.newOutputStream(to)) {
            model.serialize(out);
        }
    }
}
