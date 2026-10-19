package app.resume.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** A map of reentrant locks by name that frees an entry when nobody holds or waits for it. */
final class KeyedLocks<K> {
    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final Map<K, Entry> entries = new HashMap<>();

    void run(K key, Runnable action) {
        call(key, () -> {
            action.run();
            return null;
        });
    }

    <T> T call(K key, java.util.function.Supplier<T> action) {
        Entry e;
        synchronized (entries) {
            e = entries.computeIfAbsent(key, k -> new Entry());
            e.users++;
        }
        e.lock.lock();
        try {
            return action.get();
        } finally {
            e.lock.unlock();
            synchronized (entries) {
                if (--e.users == 0) entries.remove(key);
            }
        }
    }

    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
