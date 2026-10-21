package app.resume.jobs.validity;

/** Outcome of re-checking one posting URL. UNKNOWN (fetch trouble, robots, 5xx) never closes a posting. */
public record Verdict(Kind kind, String reason) {
    public enum Kind { OPEN, CLOSED, UNKNOWN }

    public static Verdict open() {
        return new Verdict(Kind.OPEN, null);
    }

    public static Verdict closed(String reason) {
        return new Verdict(Kind.CLOSED, reason);
    }

    public static Verdict unknown(String reason) {
        return new Verdict(Kind.UNKNOWN, reason);
    }
}
