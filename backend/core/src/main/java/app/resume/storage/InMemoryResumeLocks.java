package app.resume.storage;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class InMemoryResumeLocks implements ResumeLocks {
    private final KeyedLocks<Long> locks = new KeyedLocks<>();

    @Override
    public <T> T withLock(long resumeId, Supplier<T> action) {
        return locks.call(resumeId, action);
    }
}
