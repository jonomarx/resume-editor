package app.resume.jobs.enrich.ml;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkillTaggerTests {
    @Test
    void writeModelWhenAsked() throws Exception {
        String to = System.getProperty("ml.write");
        if (to == null) return; // normal runs never write
        var m = SkillTagTrainer.train();
        SkillTagTrainer.write(m, Path.of(to));
        assertThat(Path.of(to)).exists();
    }

    @Test
    void corpusHasSamples() throws Exception {
        assertThat(SkillCorpus.samples().size()).isGreaterThan(1000);
    }

    static final List<String> FIXTURES = List.of("labelled-postings.json", "labelled-postings-long.json", "labelled-postings-aliased.json");

    @Test
    void noEvaluationSentenceIsInTheTrainingData() throws Exception {
        java.util.Set<String> training = new java.util.HashSet<>();
        for (var sample : SkillCorpus.samples()) training.add(String.join(" ", sample.getSentence()).toLowerCase());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        int checked = 0;
        List<String> leaks = new java.util.ArrayList<>();
        for (String f : FIXTURES) {
            var root = mapper.readTree(getClass().getResourceAsStream("/fixtures/enrichment/" + f));
            for (var p : root.get("postings")) {
                List<String[]> sentences = new java.util.ArrayList<>(SkillTokenizer.sentences(p.get("text").asText()));
                sentences.addAll(SkillTokenizer.sentences(p.get("title").asText()));
                for (String[] sentence : sentences) {
                    checked++;
                    if (training.contains(String.join(" ", sentence).toLowerCase())) leaks.add(String.join(" ", sentence));
                }
            }
        }
        assertThat(checked).isGreaterThan(300);
        assertThat(leaks).as("evaluation sentences found in the training data").isEmpty();
    }

    @Test
    void trainingIsReproducibleAndTheCommittedModelMatchesIt() throws Exception {
        var a = new SkillSpanModel(SkillTagTrainer.train());
        var b = new SkillSpanModel(SkillTagTrainer.train());
        var committed = SkillSpanModel.bundled();
        for (String probe : List.of("Experience with Python, SQL and Docker.", "Go to market with new products.", "Join us in Spring 2027.",
                "Tech stack: TypeScript, React, Node.js", "Strong C++ and C# skills\nLanguages: Java, Rust, Go", "We value: Swift response times.")) {
            assertThat(a.spans(probe)).as(probe).isEqualTo(b.spans(probe)).isEqualTo(committed.spans(probe));
        }
    }

    @Test
    void inferenceIsDeterministicAndOutputLinksOnlyToVocabularyTags() {
        var vocab = List.of(new app.resume.vocabulary.VocabularyTag(7, "PostgreSQL", "postgresql", app.resume.vocabulary.VocabularyCategory.FRAMEWORK, List.of()),
                new app.resume.vocabulary.VocabularyTag(9, "Kubernetes", "kubernetes", app.resume.vocabulary.VocabularyCategory.FRAMEWORK, List.of("k8s")));
        var tagger = new MlTagger(SkillSpanModel.bundled(), vocab);
        String text = "Experience with Kubernetes, Terraform and PostgreSQL. Familiar with Kuberntes.";
        var first = tagger.tag("Intern", text);
        var second = tagger.tag("Intern", text);
        assertThat(first).isEqualTo(second);
        assertThat(first.tags()).isSubsetOf(7L, 9L);
        assertThat(first.tags()).contains(9L);
        assertThat(first.unknownSpans()).doesNotContain("Kubernetes");
    }

    @Test
    void linkerHandlesExactFuzzyAndUnknown() {
        var vocab = List.of(new app.resume.vocabulary.VocabularyTag(1, "Kubernetes", "kubernetes", app.resume.vocabulary.VocabularyCategory.FRAMEWORK, List.of("k8s")),
                new app.resume.vocabulary.VocabularyTag(2, "C++", "c-plus-plus", app.resume.vocabulary.VocabularyCategory.LANGUAGE, List.of()));
        var linker = new SkillLinker(vocab);
        assertThat(linker.link("kubernetes")).contains(1L);
        assertThat(linker.link("K8S")).contains(1L);
        assertThat(linker.link("Kuberntes")).contains(1L);       // one letter off
        assertThat(linker.link("C++")).contains(2L);
        assertThat(linker.link("Terraform")).isEmpty();            // unknown: reported, never a tag
        assertThat(linker.link("Kubernetes Engine Cluster")).isEmpty();
        assertThat(linker.link("c")).isEmpty();
        assertThat(linker.link("x".repeat(100))).isEmpty();
    }

    @Test
    void hostileTextIsBoundedAndFast() {
        var model = SkillSpanModel.bundled();
        for (String hostile : List.of("Python ".repeat(50_000), "C++, ".repeat(20_000), "a".repeat(1_000_000), "\n".repeat(100_000) + "Java",
                "(".repeat(30_000), "Experience with " + "x".repeat(19_000))) {
            long t0 = System.nanoTime();
            var spans = model.spans(hostile);
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(3_000);
            assertThat(spans.size()).isLessThanOrEqualTo(SkillTokenizer.MAX_TOKENS);
        }
        assertThat(SkillTokenizer.sentences(null)).isEmpty();
        int tokens = SkillTokenizer.sentences("Python ".repeat(50_000)).stream().mapToInt(a -> a.length).sum();
        assertThat(tokens).isLessThanOrEqualTo(SkillTokenizer.MAX_TOKENS);
    }
}
