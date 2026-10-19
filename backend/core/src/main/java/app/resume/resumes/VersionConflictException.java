package app.resume.resumes;

/** The caller's baseVersion is not the current version; the API answers 409 with `currentVersion`. */
public class VersionConflictException extends RuntimeException {
    private final long currentVersion;

    public VersionConflictException(long currentVersion) {
        super("stale base version; current is " + currentVersion);
        this.currentVersion = currentVersion;
    }

    public long currentVersion() { return currentVersion; }
}
