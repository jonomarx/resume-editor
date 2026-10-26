package app.resume.feed;

import java.util.Optional;

/** The user's own status for a posting (contract `JobUserStatus`). */
public enum JobUserStatus {
    NEW, SAVED, DISMISSED, APPLIED;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Optional<JobUserStatus> parse(String wire) {
        if (wire == null) return Optional.empty();
        for (JobUserStatus s : values()) if (s.wire().equals(wire)) return Optional.of(s);
        return Optional.empty();
    }
}
