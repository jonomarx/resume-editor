package app.resume.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Concurrency rules for resume files (DESIGN section 8). Tagged postgres: all concurrency tests run in the slow loop. */
@Tag("postgres")
class StorageConcurrencyPostgresTests {
    @TempDir Path dir;

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    <T> List<Future<T>> runAll(int n, java.util.function.IntFunction<Callable<T>> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<T> c = task.apply(i);
            fs.add(pool.submit(() -> { go.await(); return c.call(); }));
        }
        go.countDown();
        pool.shutdown();
        return fs;
    }

    @Test
    void parallelWritersOfTheSameVersionHaveExactlyOneWinnerAndNoCorruption() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir);
        String key = StorageKeys.resumeVersion(1, 1, 5);
        int n = 24;
        List<Future<Boolean>> results = runAll(n, i -> () -> {
            try {
                storage.put(key, b("content-of-writer-" + i + "-" + "x".repeat(200_000)));
                return true;
            } catch (KeyAlreadyExistsException e) {
                return false;
            }
        });
        int winners = 0;
        for (Future<Boolean> f : results) if (f.get()) winners++;
        assertThat(winners).isEqualTo(1);
        String stored = new String(storage.get(key).orElseThrow(), StandardCharsets.UTF_8);
        assertThat(stored).matches("content-of-writer-\\d+-x{200000}"); // one writer's complete content
        assertThat(storage.tempFilesIn(key)).isZero();
    }

    @Test
    void parallelWritersOfDifferentVersionsOfOneResumeAllSucceed() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir);
        int n = 32;
        List<Future<Boolean>> results = runAll(n, i -> () -> {
            storage.put(StorageKeys.resumeVersion(1, 2, i + 1), b("v" + (i + 1)));
            return true;
        });
        for (Future<Boolean> f : results) assertThat(f.get()).isTrue();
        for (int i = 1; i <= n; i++) {
            assertThat(storage.get(StorageKeys.resumeVersion(1, 2, i)).orElseThrow()).isEqualTo(b("v" + i));
        }
        try (var files = Files.list(dir.resolve("users/1/resumes/2"))) {
            assertThat(files.count()).isEqualTo(n);
        }
    }

    @Test
    void readersNeverSeeAPartialFileWhileItIsBeingWritten() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir);
        String key = StorageKeys.resumeVersion(1, 3, 1);
        byte[] big = new byte[8_000_000];
        java.util.Arrays.fill(big, (byte) 'z');
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger partial = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                storage.get(key).ifPresent(v -> { if (v.length != big.length) partial.incrementAndGet(); });
            }
        });
        reader.start();
        storage.put(key, big);
        done.set(true);
        reader.join();
        assertThat(partial.get()).isZero();
    }

    @Test
    void theResumeLockAllowsOneWriterAtATimeAndIsPerResume() throws Exception {
        InMemoryResumeLocks locks = new InMemoryResumeLocks();
        int[] counter = {0}; // deliberately unsynchronized: only the lock protects it
        int n = 16;
        List<Future<Boolean>> same = runAll(n, i -> () -> {
            for (int k = 0; k < 500; k++) locks.withLock(9L, () -> { counter[0]++; });
            return true;
        });
        for (Future<Boolean> f : same) f.get();
        assertThat(counter[0]).isEqualTo(n * 500);

        // a held lock on resume 1 does not block resume 2
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> locks.withLock(1L, () -> {
            holding.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        holding.await();
        Future<String> other = pool.submit(() -> locks.withLock(2L, () -> "ok"));
        assertThat(other.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("ok");
        release.countDown();
        holder.get();
        pool.shutdown();
    }
}
