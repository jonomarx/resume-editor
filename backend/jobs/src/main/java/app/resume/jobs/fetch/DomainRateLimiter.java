package app.resume.jobs.fetch;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Spaces requests to the same host at least {@code minIntervalMillis} apart. Thread-safe; waits outside the lock. */
public final class DomainRateLimiter {
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final long minIntervalMillis;
    private final LongSupplier clockMillis;
    private final Sleeper sleeper;
    private final Map<String, Long> nextSlot = new HashMap<>();

    public DomainRateLimiter(long minIntervalMillis) {
        this(minIntervalMillis, System::currentTimeMillis, Thread::sleep);
    }

    public DomainRateLimiter(long minIntervalMillis, LongSupplier clockMillis, Sleeper sleeper) {
        this.minIntervalMillis = minIntervalMillis;
        this.clockMillis = clockMillis;
        this.sleeper = sleeper;
    }

    /** Blocks until a request to {@code host} is allowed, and reserves that slot. */
    public void acquire(String host) {
        long wait;
        synchronized (this) {
            long now = clockMillis.getAsLong();
            long slot = Math.max(now, nextSlot.getOrDefault(host, now));
            nextSlot.put(host, slot + minIntervalMillis);
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("interrupted while rate limiting " + host, e);
            }
        }
    }
}
