package app.resume.storage;

import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Concurrency rules for resume files (DESIGN section 8). Tagged postgres: all concurrency tests run in the slow loop. */
@PostgresTest
class StorageConcurrencyPostgresTests extends PostgresContainerSupport {
    @TempDir Path dir;
    @Autowired FileRegistry registry;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        LocalFileStorage storage = new LocalFileStorage(dir, registry);
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
    void concurrentSameKeyPutsLeaveExactlyOneRegistryRowWhatEverTheContent() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir, registry);
        String key = StorageKeys.resumeVersion(9, 9, 1);
        List<Future<Boolean>> different = runAll(16, i -> () -> {
            try { storage.put(key, b("different-" + i)); return true; } catch (KeyAlreadyExistsException e) { return false; }
        });
        int winners = 0;
        for (Future<Boolean> f : different) if (f.get()) winners++;
        assertThat(winners).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from file_registry where file_key = ?", Integer.class, key)).isEqualTo(1);

        String same = StorageKeys.resumeVersion(9, 9, 2);
        List<Future<Boolean>> identical = runAll(16, i -> () -> { storage.put(same, b("identical")); return true; });
        for (Future<Boolean> f : identical) assertThat(f.get()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from file_registry where file_key = ?", Integer.class, same)).isEqualTo(1);
    }

    @Test
    void twoStorageInstancesOverOneDirectoryStillHaveExactlyOneWinner() throws Exception {
        LocalFileStorage one = new LocalFileStorage(dir, registry);
        LocalFileStorage two = new LocalFileStorage(dir, registry); // separate in-process locks: only the hard link guards
        String key = StorageKeys.resumeVersion(9, 10, 1);
        List<Future<Boolean>> results = runAll(20, i -> () -> {
            try { (i % 2 == 0 ? one : two).put(key, b("writer-" + i + "-" + "y".repeat(100_000))); return true; }
            catch (KeyAlreadyExistsException e) { return false; }
        });
        int winners = 0;
        for (Future<Boolean> f : results) if (f.get()) winners++;
        assertThat(winners).isEqualTo(1);
        assertThat(new String(one.get(key).orElseThrow(), StandardCharsets.UTF_8)).matches("writer-\\d+-y{100000}");
        assertThat(one.tempFilesIn(key)).isZero();
        byte[] stored = one.get(key).orElseThrow(); // the registry row describes exactly the bytes that won
        var row = registry.find(key).orElseThrow();
        assertThat(row.sha256()).isEqualTo(FileRegistryTestSupport.sha256(stored));
        assertThat(row.size()).isEqualTo(stored.length);
    }

    @Test
    void parallelWritersOfDifferentVersionsOfOneResumeAllSucceed() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir, registry);
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
        LocalFileStorage storage = new LocalFileStorage(dir, registry);
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
    void deleteRacingAPutOfTheSameKeyNeverLeavesAFileWithoutARow() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir, registry);
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        int rounds = 300;
        for (int round = 0; round < rounds; round++) {
            String key = StorageKeys.resumeVersion(77, 1000 + round, 1);
            storage.put(key, b("original"));
            List<Future<Boolean>> r = runAll(2, i -> () -> {
                if (i == 0) return storage.delete(key);
                try { storage.put(key, b("replacement")); return true; } catch (KeyAlreadyExistsException e) { return false; }
            });
            for (Future<Boolean> f : r) f.get();
            boolean file = storage.exists(key);
            var row = registry.find(key);
            assertThat(row.isPresent()).as("round " + round + ": file and row exist together").isEqualTo(file);
            if (file) {
                byte[] stored = storage.get(key).orElseThrow();
                assertThat(row.get().sha256()).as("round " + round).isEqualTo(FileRegistryTestSupport.sha256(stored));
                tx.executeWithoutResult(s -> registry.commit(key)); // the winning writer's commit succeeds
            }
        }
    }
}
