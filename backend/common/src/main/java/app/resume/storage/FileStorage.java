package app.resume.storage;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Where resume files live (DESIGN sections 3 and 8). Implementations are chosen by the FILE_STORAGE environment variable
 * (`local` now, `s3` later); application code depends on this interface only.
 *
 * <p>Keys are relative, slash-separated and made of safe segments (see {@link StorageKeys}); for resume files use
 * {@link StorageKeys#resumeVersion}. Files are immutable: a key is written once. Derive keys from ids with
 * {@link StorageKeys}, never from a stored URI (the URI embeds the storage location, which may move).
 *
 * <p>Every file has a row in the Postgres file registry, so {@link #list} behaves the same for every implementation.
 * Lifecycle of the caller (for example the master-resume save):
 * <ol>
 *   <li>inside the resume's lock, {@link #put} the next version: the registry gets a PENDING row first, in its own
 *       transaction (it survives a rollback or crash of the caller), then the file is written;</li>
 *   <li>in the transaction that commits the row referencing the file, call {@link #commit} for the same key.</li>
 * </ol>
 * A file left PENDING (crash or rollback) is an orphan that the reconciliation worker (US-070) finds through
 * {@link #list} with {@link FileState#PENDING}.
 */
public interface FileStorage {
    /**
     * Registers the key as PENDING (own transaction), then stores the bytes, and returns the URI to keep in the
     * database (`file:` for local disk). The write is atomic: readers see either no file or the complete file. The
     * registry insert uses its own transaction, so a caller that already holds a pooled database connection needs a
     * second one available while it calls this.
     *
     * @throws KeyAlreadyExistsException if the key already holds different bytes (putting identical bytes again is
     *         a harmless no-op that returns the same URI, so a retry after a crash is safe; a PENDING key whose file
     *         was never written may be re-registered with new bytes)
     * @throws InvalidStorageKeyException if the key is not a safe relative key
     */
    URI put(String key, byte[] bytes);

    /**
     * Marks the key COMMITTED. Call it inside the transaction that commits the row referencing the file: it joins that
     * transaction, so it commits or rolls back with it. Committing an already committed key does nothing.
     *
     * @throws IllegalStateException if there is no active transaction or the key was never put
     */
    void commit(String key);

    /** The stored bytes, or empty if the key does not exist. */
    Optional<byte[]> get(String key);

    /**
     * Removes the file, then its registry row; returns false if there was none. Never call this before the matching
     * row is committed; normally only the reconciliation worker deletes.
     */
    boolean delete(String key);

    boolean exists(String key);

    /**
     * Registry entries whose key starts with the prefix (for example `users/7/resumes/42/`), in key order, in the given
     * state, answered from the database rather than the storage backend. Use COMMITTED for files in use and PENDING
     * (check {@link StoredFile#createdAt}) to find orphans.
     */
    List<StoredFile> list(String prefix, FileState state);
}
