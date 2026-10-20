package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.tags.TagNormalizer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TagNormalizerTests {
    @Test
    void normalizationTable() {
        Map<String, String> table = new java.util.LinkedHashMap<>();
        table.put("C", "c");
        table.put("C++", "c-plus-plus");
        table.put("C#", "c-sharp");
        table.put("F#", "f-sharp");
        table.put("Node.js", "node-js");
        table.put(".NET", "net");
        table.put("k8s!", "k8s");
        table.put("café", "caf");
        table.put("  PostgreSQL ", "postgresql");
        table.put("Machine   Learning", "machine-learning");
        table.put("--Spring  Boot--", "spring-boot");
        table.put("A+B", "a-plus-b");
        table.put("+", "plus");
        table.put("#", "sharp");
        table.put("Ⅻ", "xii"); // NFKC first, then lowercase
        table.put("ＡＢＣ", "abc"); // full-width ABC
        table.forEach((in, out) -> assertThat(TagNormalizer.slug(in)).as(in).isEqualTo(out));
    }

    @Test
    void cCPlusPlusAndCSharpAreThreeDistinctSlugs() {
        assertThat(java.util.Set.of(TagNormalizer.slug("C"), TagNormalizer.slug("C++"), TagNormalizer.slug("C#"))).hasSize(3);
    }

    @Test
    void emptyWhenNothingUsableAndOutputAlwaysMatchesTheSlugPattern() {
        assertThat(TagNormalizer.slug("   ")).isEmpty();
        assertThat(TagNormalizer.slug("!!!")).isEmpty();
        assertThat(TagNormalizer.slug(".")).isEmpty();
        assertThat(TagNormalizer.slug(null)).isEmpty();
        for (String in : new String[] {"A b", "x__y", "é-é-a", "9 lives", "ⅫⅫ", "Java 17!", "C++/C#", "+ +"}) {
            String out = TagNormalizer.slug(in);
            if (!out.isEmpty()) assertThat(out).matches("[a-z0-9]+(-[a-z0-9]+)*");
        }
    }

    @Test
    void aliasKeyIsTrimmedAndLowerCasedAndPrintableAsciiIsChecked() {
        assertThat(TagNormalizer.aliasKey("  C++ ")).isEqualTo("c++");
        assertThat(TagNormalizer.aliasKey("Node.JS")).isEqualTo("node.js");
        assertThat(TagNormalizer.isPrintableAscii("C++ / .NET #1 ~!")).isTrue();
        assertThat(TagNormalizer.isPrintableAscii("café")).isFalse();
        assertThat(TagNormalizer.isPrintableAscii("tab\there")).isFalse();
        assertThat(TagNormalizer.isPrintableAscii("\u007F")).isFalse(); // DEL is a control character
        assertThat(TagNormalizer.isPrintableAscii("")).isTrue();
    }

    @Test
    void isSlugRecognizesCanonicalSlugsOnly() {
        assertThat(TagNormalizer.isSlug("postgresql")).isTrue();
        assertThat(TagNormalizer.isSlug("c-plus-plus")).isTrue();
        assertThat(TagNormalizer.isSlug("Node")).isFalse();
        assertThat(TagNormalizer.isSlug("a--b")).isFalse();
        assertThat(TagNormalizer.isSlug("-a")).isFalse();
        assertThat(TagNormalizer.isSlug("")).isFalse();
        assertThat(TagNormalizer.isSlug(null)).isFalse();
    }
}
