package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.tags.TagNormalizer;
import org.junit.jupiter.api.Test;

class TagNormalizerTests {
    @Test
    void trimsLowercasesAndCollapsesRunsOfOtherCharacters() {
        assertThat(TagNormalizer.slug("  PostgreSQL ")).isEqualTo("postgresql");
        assertThat(TagNormalizer.slug("Node.js")).isEqualTo("node-js");
        assertThat(TagNormalizer.slug("Machine   Learning")).isEqualTo("machine-learning");
        assertThat(TagNormalizer.slug("--Spring  Boot--")).isEqualTo("spring-boot");
        assertThat(TagNormalizer.slug("C++")).isEqualTo("c");
    }

    @Test
    void nfkcRunsBeforeLowercasingAndNothingIsTransliterated() {
        assertThat(TagNormalizer.slug("Ⅻ")).isEqualTo("xii"); // roman numeral twelve: NFKC gives "XII", then lowercase
        assertThat(TagNormalizer.slug("ＡＢＣ")).isEqualTo("abc"); // full-width ABC
        assertThat(TagNormalizer.slug("café")).isEqualTo("caf"); // not transliterated: the accented letter is a separator
    }

    @Test
    void emptyWhenNothingUsableAndOutputAlwaysMatchesTheSlugPattern() {
        assertThat(TagNormalizer.slug("   ")).isEmpty();
        assertThat(TagNormalizer.slug("!!!")).isEmpty();
        assertThat(TagNormalizer.slug(null)).isEmpty();
        for (String in : new String[] {"A b", "x__y", "é-é-a", "9 lives", "ⅫⅫ", "Java 17!"}) {
            String out = TagNormalizer.slug(in);
            if (!out.isEmpty()) assertThat(out).matches("[a-z0-9]+(-[a-z0-9]+)*");
        }
    }

    @Test
    void isSlugRecognizesCanonicalSlugsOnly() {
        assertThat(TagNormalizer.isSlug("postgresql")).isTrue();
        assertThat(TagNormalizer.isSlug("node-js")).isTrue();
        assertThat(TagNormalizer.isSlug("Node")).isFalse();
        assertThat(TagNormalizer.isSlug("a--b")).isFalse();
        assertThat(TagNormalizer.isSlug("-a")).isFalse();
        assertThat(TagNormalizer.isSlug("")).isFalse();
        assertThat(TagNormalizer.isSlug(null)).isFalse();
    }
}
