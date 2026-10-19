package app.resume.storage;

import java.util.function.Supplier;

/**
 * One writer per resume at a time (DESIGN section 8). The master-resume service wraps "read current version, write
 * the next file, commit the row" in {@link #withLock}. The lock is in-process, which is enough while there is a single
 * app instance; the row's optimistic version check remains the cross-instance guard.
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
