package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PostedDatesTests {
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "2026-10-01|2026-10-01T00:00:00Z",
            "2026-10-01T09:30:00Z|2026-10-01T09:30:00Z",
            "2026-04-13T04:49:05-04:00|2026-04-13T08:49:05Z",
            "2026-08-01T10:00:00.000+00:00|2026-08-01T10:00:00Z",
            "2026-10-01T09:30:00|2026-10-01T09:30:00Z",
            "2026-10-01 09:30:00|2026-10-01T09:30:00Z",
            "  2026-10-01  |2026-10-01T00:00:00Z",
            "2026-10-21|2026-10-21T00:00:00Z",
            "2026-10-22|NULL",
            "2027-01-01|NULL",
            "1999-12-31|NULL",
            "2026-02-30|NULL",
            "yesterday|NULL",
            "10/01/2026|NULL",
            "NULL|NULL",
            "''|NULL"})
    void parse(String raw, Instant expected) {
        assertThat(PostedDates.parse(raw, NOW)).isEqualTo(expected);
    }

    @Test
    void tooLongValuesAreRejected() {
        assertThat(PostedDates.parse("2026-10-01" + " ".repeat(50) + "x", NOW)).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "Posted on 2026-10-01|2026-10-01T00:00:00Z",
            "Great role. Date posted: 2026-09-15. Apply now.|2026-09-15T00:00:00Z",
            "Posted: Oct 1, 2026|2026-10-01T00:00:00Z",
            "Published on September 3, 2026|2026-09-03T00:00:00Z",
            "Posted on Sept 3, 2026|2026-09-03T00:00:00Z",
            "Posted on 2027-01-01|NULL",
            "Posted on 2026-13-40|NULL",
            "Apply by 2026-11-01|NULL",
            "Closing date: Oct 30, 2026|NULL",
            "We posted a job last week|NULL",
            "Start date 2026-06-01|NULL"})
    void fromText(String text, Instant expected) {
        assertThat(PostedDates.fromText(text, NOW)).isEqualTo(expected);
    }

    @Test
    void earliestCredibleDateWins() {
        Instant old = Instant.parse("2026-05-01T00:00:00Z");
        Instant newer = Instant.parse("2026-09-01T00:00:00Z");
        assertThat(PostedDates.earliest(null, newer)).isEqualTo(newer);
        assertThat(PostedDates.earliest(old, newer)).isEqualTo(old);
        assertThat(PostedDates.earliest(newer, old)).isEqualTo(old);
        assertThat(PostedDates.earliest(old, null)).isEqualTo(old);
        assertThat(PostedDates.earliest(null, null)).isNull();
    }
}
