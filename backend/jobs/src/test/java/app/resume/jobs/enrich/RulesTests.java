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
            "Expected graduation date: 2027|graduation date: 2027",
            "Juniors or seniors only|Juniors or seniors",
            "Graduating soon|NULL",
            "Founded in 2019, we are graduates of YC|NULL"})
    void classYear(String text, String expected) {
        assertThat(EligibilityRules.classYear(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Great team. Must be authorized to work in the US. Apply now.|authorization_required",
            "Applicants must be U.S. citizens.|authorization_required",
            "We do not offer visa sponsorship|does_not_sponsor",
            "Visa sponsorship is not available for this role.|does_not_sponsor",
            "We cannot sponsor visas.|does_not_sponsor",
            "We do sponsor visas for interns.|sponsors",
            "Visa sponsorship available.|sponsors",
            "Must be authorized to work in the US; we cannot offer visa sponsorship.|does_not_sponsor",
            "Authorized to work in the US. We will sponsor visas.|sponsors",
            "Security clearance is not required.|NULL",
            "Sponsorship is not required to apply.|NULL",
            "Great team and snacks|NULL",
            "NULL|NULL"})
    void workAuthorizationIsNormalized(String text, String expected) {
        assertThat(EligibilityRules.workAuthorization(text)).isEqualTo(expected);
    }

    @Test
    void authorizationSentencesAreJudgedSeparatelyAcrossLines() {
        assertThat(EligibilityRules.workAuthorization("We do not offer visa sponsorship\nBring snacks")).isEqualTo("does_not_sponsor");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Last summer 2019 we grew. Join us in Summer 2027.|Summer 2027",
            "Since fall 2020 we have hired. The internship runs Winter 2027.|Winter 2027",
            "Summer 2027 or Fall 2026|Summer 2027",
            "Last summer 2019 only|NULL"})
    void seasonPrefersTheTargetSeasonOverPastMentions(String text, String expected) {
        assertThat(EligibilityRules.season(text)).isEqualTo(expected);
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
        String body = "Requirements\n" + "word\n".repeat(10_000);
        assertThat(RequirementSections.parse(body).basic().length()).isLessThanOrEqualTo(RequirementSections.MAX_FIELD);
    }
}
