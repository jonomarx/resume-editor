package app.resume.storage;

import java.util.function.Supplier;

/**
 * One writer per resume at a time (DESIGN section 8). Lock order for a save: take the lock, read the current version,
 * {@link FileStorage#put} the next file, commit the resume row (and {@link FileStorage#commit} in the same
 * transaction), release. Write the file first and never delete before the row commits.
 *
 * <p>The lock is in-process (one JVM, which is enough while there is a single app instance) and keyed by resume id
 * only, so it does not protect a second instance: the row's optimistic version (409 on a stale save) stays the
 * cross-instance guard. Do not hold the locks of two different resumes at once; opposite acquisition orders can
 * deadlock.
 */
public interface ResumeLocks {
    <T> T withLock(long resumeId, Supplier<T> action);

    default void withLock(long resumeId, Runnable action) {
        withLock(resumeId, () -> {
            action.run();
            return null;
        });
    }
}
