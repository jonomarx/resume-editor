package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostingNormalizerTests {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "HTTPS://Example.COM/Jobs/42|https://example.com/Jobs/42",
            "https://example.com/jobs/42/|https://example.com/jobs/42",
            "https://example.com/|https://example.com/",
            "https://example.com|https://example.com/",
            "https://example.com?b=1|https://example.com/?b=1",
            "https://example.com/a%2fb%c3%a9|https://example.com/a%2Fb%C3%A9",
            "https://example.com/a?q=%2f%e9|https://example.com/a?q=%2F%E9",
            "https://example.com:443/a|https://example.com/a",
            "http://example.com:80/a|http://example.com/a",
            "https://example.com:8443/a|https://example.com:8443/a",
            "https://example.com/a#apply|https://example.com/a",
            "https://example.com/a?utm_source=x&utm_medium=y&id=7|https://example.com/a?id=7",
            "https://example.com/a?gh_src=abc&gh_jid=99|https://example.com/a?gh_jid=99&gh_src=abc",
            "https://example.com/a?source=li&src=x|https://example.com/a?source=li&src=x",
            "https://example.com/a?mc_cid=1&mc_eid=2&ref_src=3&utm_term=4|https://example.com/a",
            "https://example.com/a?fbclid=1&gclid=2&ref=3|https://example.com/a",
            "https://example.com/a?z=1&a=2|https://example.com/a?a=2&z=1",
            "https://example.com/a?UTM_Campaign=x&id=7|https://example.com/a?id=7",
            "  https://example.com/a  |https://example.com/a",
            "https://example.com/a?id=7&&|https://example.com/a?id=7"})
    void canonicalUrl(String raw, String expected) {
        assertThat(PostingNormalizer.canonicalUrl(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "example.com/a", "ftp://example.com/a", "javascript:alert(1)", "https://", "ht tp://x", "https://example.com/a%zz"})
    void rejectsNonHttpUrls(String raw) {
        assertThatThrownBy(() -> PostingNormalizer.canonicalUrl(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PostingNormalizer.canonicalUrl(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urlsDifferingOnlyByTrackingShareAHash() {
        String a = PostingNormalizer.canonicalUrl("https://x.com/j/1?utm_source=li");
        String b = PostingNormalizer.canonicalUrl("https://X.com/j/1/#top");
        assertThat(PostingNormalizer.urlHash(a)).isEqualTo(PostingNormalizer.urlHash(b)).hasSize(64);
        assertThat(PostingNormalizer.urlHash(a))
                .isNotEqualTo(PostingNormalizer.urlHash(PostingNormalizer.canonicalUrl("https://x.com/j/2")));
    }

    @Test
    void contentHashIgnoresWhitespaceAndCaseButNotRealChanges() {
        String base = PostingNormalizer.contentHash("Intern", "Acme", "Seattle, WA", "Build things.\nWith care.");
        assertThat(PostingNormalizer.contentHash(" intern ", "ACME", "Seattle,  WA", "Build things. With   care. "))
                .isEqualTo(base);
        assertThat(PostingNormalizer.contentHash("Intern", "Acme", "Seattle, WA", "Build other things."))
                .isNotEqualTo(base);
        assertThat(PostingNormalizer.contentHash("Intern", "Acme", null, null)).hasSize(64);
    }

    @Test
    void contentHashFieldsDoNotBleedTogether() {
        assertThat(PostingNormalizer.contentHash("ab", "c", "", "")).isNotEqualTo(PostingNormalizer.contentHash("a", "bc", "", ""));
    }

    @Test
    void dedupeKeyMatchesTheSameRoleAtDifferentUrls() {
        String a = PostingNormalizer.dedupeKey("Acme, Inc.", "Software Engineer Intern - Summer 2027", "Seattle, WA");
        String b = PostingNormalizer.dedupeKey("ACME Inc", " software engineer intern (summer 2027) ", "seattle wa");
        assertThat(a).isEqualTo(b);
        assertThat(PostingNormalizer.dedupeKey("Café", "Intern", "Zürich"))
                .isEqualTo(PostingNormalizer.dedupeKey("Cafe", "Intern", "Zurich"));
    }

    @Test
    void dedupeKeyDistinguishesDifferentRoles() {
        String a = PostingNormalizer.dedupeKey("Acme", "Software Intern", "Seattle");
        assertThat(PostingNormalizer.dedupeKey("Acme", "Data Intern", "Seattle")).isNotEqualTo(a);
        assertThat(PostingNormalizer.dedupeKey("Acme", "Software Intern", "Austin")).isNotEqualTo(a);
        assertThat(PostingNormalizer.dedupeKey("Beta", "Software Intern", "Seattle")).isNotEqualTo(a);
        assertThat(PostingNormalizer.dedupeKey("Acme", "Software Intern", null)).isNotEqualTo(a);
    }
}
