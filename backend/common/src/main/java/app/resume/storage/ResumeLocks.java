package app.resume.storage;

import java.util.function.Supplier;

/**
 * The lock-scoped unit of work for one resume (DESIGN section 8). {@link #withResumeLock} takes the resume's lock, runs
 * {@code work} inside a database transaction, commits that transaction, and only then releases the lock, so the lock
 * covers the commit: a second writer can never read the old version after the first writer has returned. A bare lock
 * around code that runs inside someone else's {@code @Transactional} method would release before the commit and defeat
 * the lock, which is why this interface offers no plain lock.
 *
 * <p>Typical save, all inside {@code work}: read the current version, check the caller's {@code baseVersion} (409 on a
 * mismatch), {@link FileStorage#put} the file for the next version, insert the version row, and call
 * {@link FileStorage#commit} for the same key. Write the file first, commit the row, never delete before commit.
 *
 * <p>The lock holds across processes on Postgres (an advisory transaction lock keyed by the resume id, released at
 * commit or rollback) and in-process everywhere (so H2 tests are serialized too). The row's optimistic version stays
 * the application-level guard (409). Do not nest calls for two different resumes: opposite orders can deadlock.
 * Calling it while a transaction is already active fails fast with an IllegalStateException (joining that transaction
 * would release the lock before the commit), so call it from non-transactional code such as a controller or service
 * method without {@code @Transactional}.
 * {@code work} must not start its own thread for database access; it runs on the caller's thread and connection.
 */
public interface ResumeLocks {
    /**
     * @param resumeId the resume whose writers are serialized
     * @param work runs in a transaction; its return value is returned after the commit. A RuntimeException rolls the
     *        transaction back, releases the lock and is rethrown unchanged.
     */
    <T> T withResumeLock(long resumeId, Supplier<T> work);

    /** Same, for work without a result. */
    default void withResumeLock(long resumeId, Runnable work) {
        withResumeLock(resumeId, () -> {
            work.run();
            return null;
        });
    }
}
