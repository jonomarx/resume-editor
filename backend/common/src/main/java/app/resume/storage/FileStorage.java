package app.resume.storage;

import java.net.URI;
import java.util.Optional;

/**
 * Where resume files live (DESIGN section 3). Implementations are chosen by the FILE_STORAGE environment variable
 * (`local` now, `s3` later); application code depends on this interface only.
 *
 * <p>Keys are relative, slash-separated and made of safe segments (see {@link StorageKeys}); for resume files use
 * {@link StorageKeys#resumeVersion}. Files are immutable: a key is written once.
 */
public interface FileStorage {
    /**
     * Stores the bytes under the key and returns the URI to keep in the database (`file:` for local disk).
     * The write is atomic: readers see either no file or the complete file, never a partial one.
     *
     * @throws KeyAlreadyExistsException if the key already holds different bytes (putting identical bytes again is
     *         a harmless no-op that returns the same URI, so a retry after a crash is safe)
     * @throws InvalidStorageKeyException if the key is not a safe relative key
     */
    URI put(String key, byte[] bytes);

    /** The stored bytes, or empty if the key does not exist. */
    Optional<byte[]> get(String key);

    /** Removes the file; returns false if there was none. Never call this before the matching row is committed. */
    boolean delete(String key);

    boolean exists(String key);
}
