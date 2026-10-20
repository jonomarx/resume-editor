package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsRulesTests {
    static final String TOKEN = "ResumeEditorBot";

    @Test
    void wildcardGroupDisallowsAndAllowOverridesWithLongerMatch() {
        var r = RobotsRules.parse("User-agent: *\nDisallow: /private\nAllow: /private/public\n", TOKEN);
        assertThat(r.allows("/jobs")).isTrue();
        assertThat(r.allows("/private/x")).isFalse();
        assertThat(r.allows("/private/public/y")).isTrue();
    }

    @Test
    void specificGroupBeatsWildcard() {
        var r = RobotsRules.parse("User-agent: *\nDisallow: /\n\nUser-agent: resumeeditorbot\nDisallow: /admin\n", TOKEN);
        assertThat(r.allows("/jobs")).isTrue();
        assertThat(r.allows("/admin/x")).isFalse();
    }

    @Test
    void emptyDisallowAndMissingGroupsAllowAll() {
        assertThat(RobotsRules.parse("User-agent: *\nDisallow:\n", TOKEN).allows("/x")).isTrue();
        assertThat(RobotsRules.parse("User-agent: otherbot\nDisallow: /\n", TOKEN).allows("/x")).isTrue();
        assertThat(RobotsRules.ALLOW_ALL.allows("/x")).isTrue();
    }

    @Test
    void wildcardsAnchorsCommentsAndGroupedAgents() {
        var r = RobotsRules.parse("# hi\nUser-agent: a\nUser-agent: *\nDisallow: /*.pdf$ # no pdfs\nDisallow: /search?*q=\n", TOKEN);
        assertThat(r.allows("/files/cv.pdf")).isFalse();
        assertThat(r.allows("/files/cv.pdf.html")).isTrue();
        assertThat(r.allows("/search?x=1&q=a")).isFalse();
        assertThat(r.allows("/jobs")).isTrue();
    }
}
