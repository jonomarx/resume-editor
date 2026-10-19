package app.resume.render;

import java.util.Optional;

/**
 * Cache of successful renders, per user and content hash (DESIGN section 8: idempotent on content hash, atomic writes).
 * Entries carry the template fingerprint they were rendered with; an entry rendered with another template is a miss.
 */
public interface RenderCache {
    record Entry(int pageCount, String log) {}

    Optional<Entry> find(long userId, String contentHash, String templateFingerprint);

    /** Stores the PDF and its metadata atomically; readers never see half of an entry. Replaces a stale entry. */
    void store(long userId, String contentHash, String templateFingerprint, byte[] pdf, int pageCount, String log);

    Optional<byte[]> pdf(long userId, String contentHash);
}
