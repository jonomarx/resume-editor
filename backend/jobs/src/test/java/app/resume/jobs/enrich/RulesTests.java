package app.resume.jobs.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RulesTests {
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Software Engineer Intern, Summer 2027|Summer 2027",
            "internship runs fall 2026 in Austin|Fall 2026",
            "Spring of 2028 co-op|Spring 2028",
            "Summer internship, no year given|NULL",
            "We hire every summer. Year 2027 plans|NULL",
            "NULL|NULL"})
    void season(String text, String expected) {
        assertThat(EligibilityRules.season(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Open to rising juniors and seniors.|rising juniors and seniors",
            "rising senior|rising senior",
            "Class of 2028 welcome|Class of 2028",
            "graduating in May 2027 or later|graduating in May 2027",
            "Expected graduation date: 2027|Expected graduation date: 2027",
            "Juniors or seniors only|Juniors or seniors",
            "Graduating soon|NULL",
            "Founded in 2019, we are graduates of YC|NULL"})
    void classYear(String text, String expected) {
        assertThat(EligibilityRules.classYear(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Great team. Must be authorized to work in the US. Apply now.|Must be authorized to work in the US.",
            "Applicants must be U.S. citizens.|Applicants must be U.S. citizens.",
            "Great team and snacks|NULL"})
    void workAuthorization(String text, String expected) {
        assertThat(EligibilityRules.workAuthorization(text)).isEqualTo(expected);
    }

    @Test
    void authorizationSentenceStopsAtALineBreak() {
        assertThat(EligibilityRules.workAuthorization("We do not offer visa sponsorship\nBring snacks")).isEqualTo("We do not offer visa sponsorship");
    }

    @Test
    void longAuthorizationSentenceIsCutAtAWordBoundary() {
        String s = "Applicants must be authorized to work " + "in the country without any need for sponsorship ".repeat(10);
        String got = EligibilityRules.workAuthorization(s);
        assertThat(got.length()).isLessThanOrEqualTo(120);
        assertThat(s.replaceAll("\\s+", " ")).startsWith(got);
    }

    @Test
    void sectionsKeepTextVerbatimAndRequireAHeading() {
        var s = RequirementSections.parse("""
                About us
                We love Java.
                Basic knowledge of Java is not a heading.
                Requirements:
                BS in CS
                Experience with SQL
                Nice to have
                Docker
                Benefits
                Snacks
                """);
        assertThat(s.basic()).isEqualTo("BS in CS\nExperience with SQL");
        assertThat(s.preferred()).isEqualTo("Docker");
    }

    @Test
    void inlineHeadingKeepsTheRestOfTheLine() {
        var s = RequirementSections.parse("Requirements: BS in CS\nPreferred: Docker");
        assertThat(s.basic()).isEqualTo("BS in CS");
        assertThat(s.preferred()).isEqualTo("Docker");
    }

    @Test
    void noHeadingMeansNoRequirementsNeverAGuess() {
        var s = RequirementSections.parse("We need someone who knows Java and Spring. Basic knowledge of Java required.");
        assertThat(s.basic()).isNull();
        assertThat(s.preferred()).isNull();
        assertThat(RequirementSections.parse(null).basic()).isNull();
    }

    @Test
    void sectionTextIsCapped() {
        String body = "Requirements\n" + "word ".repeat(10_000);
        assertThat(RequirementSections.parse(body).basic().length()).isLessThanOrEqualTo(RequirementSections.MAX_FIELD);
    }
}
