package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.tags.TagNormalizer;
import org.junit.jupiter.api.Test;

class TagNormalizerTests {
    @Test
    void normalizesCaseWhitespaceAndPunctuation() {
        assertThat(TagNormalizer.slug("  PostgreSQL ")).isEqualTo("postgresql");
        assertThat(TagNormalizer.slug("Node.js")).isEqualTo("node-js");
        assertThat(TagNormalizer.slug("Machine   Learning")).isEqualTo("machine-learning");
        assertThat(TagNormalizer.slug("--Spring  Boot--")).isEqualTo("spring-boot");
        assertThat(TagNormalizer.slug("Café")).isEqualTo("cafe");
    }

    @Test
    void keepsCLikeLanguagesDistinct() {
        assertThat(TagNormalizer.slug("C")).isEqualTo("c");
        assertThat(TagNormalizer.slug("C++")).isEqualTo("c-plus-plus");
        assertThat(TagNormalizer.slug("C#")).isEqualTo("c-sharp");
    }

    @Test
    void emptyWhenNothingUsable() {
        assertThat(TagNormalizer.slug("   ")).isEmpty();
        assertThat(TagNormalizer.slug("!!!")).isEmpty();
        assertThat(TagNormalizer.slug(null)).isEmpty();
    }
}
