package app.resume.jobs.fetch;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the "published" date a source gives into an Instant, or null. Never guessed: an unparseable value, a date more than a
 * day in the future (clock skew allowance) and a date before 2000 are all null. A date without a time zone is read as UTC; a
 * date without a time is midnight UTC. Text patterns are deliberately narrow ("Posted on 2026-10-01").
 */
public final class PostedDates {
    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Duration SKEW = Duration.ofDays(1);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH);
    private static final Pattern TEXT = Pattern.compile("(?i)\\b(?:date\\s{1,3}posted|posted(?:\\s{1,3}on)?|published(?:\\s{1,3}on)?)\\s{0,3}[:\\-]?\\s{0,3}"
            + "(\\d{4}-\\d{2}-\\d{2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]{0,6}\\.?\\s{1,3}\\d{1,2},\\s{1,3}\\d{4})");

    private PostedDates() {}

    public static Instant parse(String raw, Instant now) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty() || s.length() > 40) return null;
        Instant t = null;
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                t = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
            } else {
                try {
                    t = OffsetDateTime.parse(s).toInstant(); // 2026-10-01T09:30:00-07:00 and ...Z
                } catch (DateTimeParseException e) {
                    t = LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC); // no zone: UTC
                }
            }
        } catch (DateTimeException e) {
            return null;
        }
        return credible(t, now) ? t : null;
    }

    /** A date a posting states about itself ("Posted on 2026-10-01", "Date posted: Oct 1, 2026"), or null. */
    public static Instant fromText(String text, Instant now) {
        if (text == null) return null;
        Matcher m = TEXT.matcher(text.length() > 20_000 ? text.substring(0, 20_000) : text);
        if (!m.find()) return null;
        String d = m.group(1).replace("Sept", "Sep").replace(".", "");
        try {
            if (d.matches("\\d{4}-\\d{2}-\\d{2}")) return parse(d, now);
            String[] parts = d.split("\\s+", 2);
            String month = parts[0].length() > 3 ? parts[0] : parts[0];
            String normalized = month.substring(0, 1).toUpperCase(Locale.ROOT) + month.substring(1).toLowerCase(Locale.ROOT) + " " + parts[1].replaceAll("\\s+", " ");
            LocalDate date;
            try {
                date = LocalDate.parse(normalized, LONG_DATE);
            } catch (DateTimeParseException e) {
                date = LocalDate.parse(normalized.replaceFirst("^(\\p{L}{3})\\p{L}*", "$1"), SHORT_DATE);
            }
            Instant t = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            return credible(t, now) ? t : null;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static boolean credible(Instant t, Instant now) {
        return t != null && !t.isBefore(EARLIEST) && !t.isAfter(now.plus(SKEW));
    }

    /** The earliest credible date: a repost never makes an old posting look new. */
    public static Instant earliest(Instant stored, Instant incoming) {
        if (incoming == null) return stored;
        if (stored == null) return incoming;
        return incoming.isBefore(stored) ? incoming : stored;
    }
}
