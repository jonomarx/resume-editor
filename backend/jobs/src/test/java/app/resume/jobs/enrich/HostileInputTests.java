package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Untrusted posting text must never overflow the stack or take noticeable time, whatever its shape (under the 20k cap, and over it). */
class HostileInputTests {
    static final List<VocabularyTag> VOCAB = List.of(
            new VocabularyTag(1, "Go", "go", VocabularyCategory.LANGUAGE, List.of("Golang")),
            new VocabularyTag(2, "C++", "c-plus-plus", VocabularyCategory.LANGUAGE, List.of()),
            new VocabularyTag(3, "Java", "java", VocabularyCategory.LANGUAGE, List.of()));

    static Stream<String> shapes() {
        return Stream.of(
                "rising juniors, ".repeat(400) + "x",
                "rising juniors, ".repeat(1200) + "x",
                "rising juniors and ".repeat(1000) + "seniors",
                "juniors or ".repeat(1500) + "x",
                "graduating" + " ".repeat(19_000) + "x",
                "a" + " ".repeat(19_000) + "x",
                "Requirements\n" + "\t".repeat(19_000),
                "Requirements:\n" + "- ".repeat(9_000),
                ("Requirements\n" + "Nice to have\n").repeat(1_000),
                ("Requirements:" + " ".repeat(30) + "x\n").repeat(600),
                "* ".repeat(10_000) + "Go",
                "Summer ".repeat(2_500) + "2027",
                "Class of ".repeat(2_000),
                "expected graduation date ".repeat(700),
                "sponsorship ".repeat(1_700),
                "not ".repeat(5_000) + "Java is not required",
                "C".repeat(19_000) + "++ Go Java",
                ("Go " + "in ".repeat(20)).repeat(300),
                "(".repeat(19_000),
                "\n".repeat(19_000) + "Requirements",
                ":".repeat(19_000),
                "Requirements: " + "a:".repeat(9_000),
                "Z".repeat(500_000),
                "rising juniors, ".repeat(100_000));
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void everyRuleSurvivesAndIsFast(String hostile) {
        long t0 = System.nanoTime();
        Enrichment e = DeterministicEnricher.fields("Intern", hostile);
        var matcher = new VocabularyTagMatcher(VOCAB);
        for (var s : DeterministicEnricher.TagStrategy.values()) DeterministicEnricher.tags(matcher, s, "Intern", hostile);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(1_000);
        assertThat(e).isNotNull();
    }

    @Test
    void theReportedStackOverflowInputIsHandled() {
        assertThat(EligibilityRules.classYear("rising juniors, ".repeat(400) + "x")).startsWith("rising juniors");
    }

    @Test
    void sectionsAreCappedInsideParse() {
        var s = RequirementSections.parse("Requirements\n" + "word\n".repeat(100_000));
        assertThat(s.basic().length()).isLessThanOrEqualTo(RequirementSections.MAX_FIELD);
    }
}
