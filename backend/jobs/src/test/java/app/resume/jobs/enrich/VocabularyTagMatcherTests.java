package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.jobs.enrich.VocabularyTagMatcher.Mode;
import app.resume.vocabulary.VocabularyCategory;
import app.resume.vocabulary.VocabularyTag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class VocabularyTagMatcherTests {
    static final List<String> NAMES = List.of("C", "C++", "C#", "Go", "R", "Java", "JavaScript", ".NET", "Node.js", "React", "Rust", "Swift");
    static final List<VocabularyTag> VOCAB = new ArrayList<>();

    static {
        for (int i = 0; i < NAMES.size(); i++) {
            List<String> aliases = NAMES.get(i).equals("React") ? List.of("reactjs") : NAMES.get(i).equals("Go") ? List.of("Golang") : List.of();
            VOCAB.add(new VocabularyTag(i + 1, NAMES.get(i), Slugs.slug(NAMES.get(i)), VocabularyCategory.LANGUAGE, aliases));
        }
    }

    static Arguments row(String text, String... expected) {
        return Arguments.of(text, Set.of(expected));
    }

    static Stream<Arguments> guarded() {
        return Stream.of(
                row("Experience with C, C++ and C#", "C", "C++", "C#"),
                row("Experience with C++ only", "C++"),
                row("Experience with C# only", "C#"),
                row("Strong C programming skills", "C"),
                row("Systems languages: C", "C"),
                row("We use .NET and Node.js", ".NET", "Node.js"),
                row("ASP.NET MVC", new String[0]),
                row("Java or JavaScript", "Java", "JavaScript"),
                row("javascript and JAVA", "Java", "JavaScript"),
                row("Skills in R, Python and SQL", "R"),
                row("Our R&D group", new String[0]),
                row("R&D experience, plus Go skills", "Go"),
                row("Go to our careers page", new String[0]),
                row("Let's go! A go-getter culture", new String[0]),
                row("Go-to-market strategy", new String[0]),
                row("Write services in Go.", "Go"),
                row("Golang (or Go) experience", "Go"),
                row("Experience with reactjs", "React"),
                row("React-based UI", "React"),
                row("Reactive programming", new String[0]),
                row("Swift or Rust experience", "Swift", "Rust"),
                row("Swift-moving team that rusts nothing", new String[0]),
                row("C-level team, plan A or plan B", new String[0]),
                row("JavaScriptCore internals", new String[0]),
                row("", new String[0]));
    }

    @ParameterizedTest
    @MethodSource("guarded")
    void guardedMatchingHandlesTrickyNamesAndTraps(String text, Set<String> expected) {
        Set<Long> got = new VocabularyTagMatcher(VOCAB).match(text, Mode.GUARDED);
        Set<String> names = new java.util.HashSet<>();
        got.forEach(id -> names.add(NAMES.get((int) (id - 1))));
        assertThat(names).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void pathologicalAndHugeTextIsBoundedAndFast() {
        String evil = "C".repeat(100_000) + "+".repeat(50_000) + " ".repeat(10_000) + "Java";
        long t0 = System.nanoTime();
        Set<Long> got = new VocabularyTagMatcher(VOCAB).match(evil, Mode.GUARDED);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000);
        assertThat(got).isEmpty(); // "Java" lies beyond the size cap, so it is never read
    }

    @org.junit.jupiter.api.Test
    void termsWithRegexCharactersAreLiteral() {
        var weird = new VocabularyTag(99, "a.b(c", "a-b-c", VocabularyCategory.DOMAIN, List.of("(.*)+"));
        var m = new VocabularyTagMatcher(List.of(weird));
        assertThat(m.match("see a.b(c here", Mode.GUARDED)).containsExactly(99L);
        assertThat(m.match("aXb(c", Mode.GUARDED)).isEmpty();
    }
}
