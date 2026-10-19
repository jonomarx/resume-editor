package app.resume.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The file registry: intent rows, commit in the caller's transaction, and list(prefix) from the database. */
@SpringBootTest
@ActiveProfiles("test")
class RegistryStorageTests {
    static final AtomicLong USERS = new AtomicLong(5000);

    @TempDir Path dir;
    @Autowired FileRegistry registry;
    @Autowired PlatformTransactionManager txManager;
    LocalFileStorage storage;
    TransactionTemplate tx;
    long uid;
    String key;

    @BeforeEach
    void setUp() {
        uid = USERS.incrementAndGet();
        key = StorageKeys.resumeVersion(uid, 1, 1);
        storage = new LocalFileStorage(dir, registry);
        tx = new TransactionTemplate(txManager);
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    FileState stateOf(String k) { return registry.find(k).orElseThrow().state(); }

    /** A storage that "crashes" after the intent row is inserted and before anything is written. */
    LocalFileStorage crashingStorage() {
        return new LocalFileStorage(dir, registry) {
            @Override protected void afterRegistered(String k) { throw new IllegalStateException("simulated crash"); }
        };
    }

    @Test
    void putRegistersAPendingRowAndTheFile() {
        URI uri = storage.put(key, b("{}"));
        var row = registry.find(key).orElseThrow();
        assertThat(row.state()).isEqualTo(FileState.PENDING);
        assertThat(row.size()).isEqualTo(2);
        assertThat(row.sha256()).hasSize(64);
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.list("users/" + uid + "/", FileState.PENDING)).singleElement()
                .satisfies(f -> { assertThat(f.uri()).isEqualTo(uri); assertThat(f.key()).isEqualTo(key); assertThat(f.createdAt()).isNotNull(); });
    }

    @Test
    void theRowExistsBeforeTheFileDoesAndACrashLeavesOnlyThePendingRow() {
        assertThatThrownBy(() -> crashingStorage().put(key, b("data"))).hasMessageContaining("simulated crash");
        assertThat(stateOf(key)).isEqualTo(FileState.PENDING);
        assertThat(storage.exists(key)).isFalse();
        // retrying the same bytes completes the write; the row stays PENDING until committed
        storage.put(key, b("data"));
        assertThat(storage.exists(key)).isTrue();
        assertThat(stateOf(key)).isEqualTo(FileState.PENDING);
    }

    @Test
    void aPendingKeyWhoseFileWasNeverWrittenCanBeReRegisteredWithNewBytes() {
        assertThatThrownBy(() -> crashingStorage().put(key, b("old"))).isInstanceOf(IllegalStateException.class);
        storage.put(key, b("new")); // takes over the unwritten intent
        assertThat(storage.get(key).orElseThrow()).isEqualTo(b("new"));
        assertThat(registry.find(key).orElseThrow().size()).isEqualTo(3);
        assertThatThrownBy(() -> storage.put(key, b("another"))).isInstanceOf(KeyAlreadyExistsException.class);
    }

    @Test
    void commitInsideACommittingTransactionFlipsToCommitted() {
        storage.put(key, b("x"));
        tx.executeWithoutResult(s -> storage.commit(key));
        assertThat(stateOf(key)).isEqualTo(FileState.COMMITTED);
        tx.executeWithoutResult(s -> storage.commit(key)); // committing twice is harmless
        assertThat(stateOf(key)).isEqualTo(FileState.COMMITTED);
    }

    @Test
    void aRolledBackCallerTransactionStillLeavesThePendingRowAndFile() {
        tx.executeWithoutResult(s -> {
            storage.put(key, b("x")); // own transaction: survives the rollback below
            s.setRollbackOnly();
        });
        assertThat(stateOf(key)).isEqualTo(FileState.PENDING);
        assertThat(storage.exists(key)).isTrue();
    }

    @Test
    void aCommitInsideARolledBackTransactionRollsBackWithIt() {
        storage.put(key, b("x"));
        tx.executeWithoutResult(s -> {
            storage.commit(key);
            s.setRollbackOnly();
        });
        assertThat(stateOf(key)).isEqualTo(FileState.PENDING);
    }

    @Test
    void commitNeedsATransactionAndAKnownKey() {
        storage.put(key, b("x"));
        assertThatThrownBy(() -> storage.commit(key)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> storage.commit(StorageKeys.resumeVersion(uid, 9, 9))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no registered file");
    }

    @Test
    void listFiltersByPrefixAndState() {
        String a = StorageKeys.resumeVersion(uid, 1, 1);
        String b2 = StorageKeys.resumeVersion(uid, 1, 2);
        String c = StorageKeys.resumeVersion(uid, 2, 1);
        for (String k : List.of(a, b2, c)) storage.put(k, b(k));
        tx.executeWithoutResult(s -> storage.commit(a));
        String resume1 = StorageKeys.resumeDirectory(uid, 1) + "/";
        assertThat(storage.list(resume1, FileState.COMMITTED)).extracting(StoredFile::key).containsExactly(a);
        assertThat(storage.list(resume1, FileState.PENDING)).extracting(StoredFile::key).containsExactly(b2);
        assertThat(storage.list("users/" + uid + "/", FileState.PENDING)).extracting(StoredFile::key)
                .containsExactly(b2, c);
        assertThat(storage.list("users/" + uid + "/resumes/3/", FileState.PENDING)).isEmpty();
        // a prefix with a trailing slash does not match a longer id ("users/5001/" vs "users/50011/")
        assertThat(storage.list("users/" + uid + "0/", FileState.PENDING)).isEmpty();
    }

    @Test
    void deleteRemovesTheFileAndTheRegistryRow() {
        storage.put(key, b("x"));
        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.exists(key)).isFalse();
        assertThat(registry.find(key)).isEmpty();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void conflictingContentIsRejectedBeforeAnythingIsWritten() {
        storage.put(key, b("first"));
        assertThatThrownBy(() -> storage.put(key, b("second"))).isInstanceOf(KeyAlreadyExistsException.class);
        assertThat(registry.find(key).orElseThrow().size()).isEqualTo(5);
        assertThat(storage.get(key).orElseThrow()).isEqualTo(b("first"));
    }

    @Test
    void underscoreInAPrefixIsALiteralNotALikeWildcard() {
        String underscore = "users/" + uid + "/res_1/v1.json";
        String lookalike = "users/" + uid + "/resX1/v1.json";
        storage.put(underscore, b("a"));
        storage.put(lookalike, b("b"));
        assertThat(storage.list("users/" + uid + "/res_1/", FileState.PENDING)).extracting(StoredFile::key)
                .containsExactly(underscore);
    }

    @Test
    void racingWritersWithDifferentBytesLeaveARowThatDescribesTheStoredFile() throws Exception {
        LocalFileStorage other = new LocalFileStorage(dir, registry); // a second instance: separate in-process locks
        for (int round = 0; round < 30; round++) {
            String k = StorageKeys.resumeVersion(uid, 100 + round, 1);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final int w = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    try { (w % 2 == 0 ? storage : other).put(k, b("writer-" + w + "-" + "q".repeat(50 + w * 13))); return true; }
                    catch (KeyAlreadyExistsException e) { return false; }
                }));
            }
            go.countDown();
            int winners = 0;
            for (var f : fs) if (f.get()) winners++;
            pool.shutdown();
            assertThat(winners).isEqualTo(1);
            byte[] stored = storage.get(k).orElseThrow();
            var row = registry.find(k).orElseThrow();
            assertThat(row.sha256()).as("round " + round).isEqualTo(FileRegistryTestSupport.sha256(stored));
            assertThat(row.size()).as("round " + round).isEqualTo(stored.length);
            assertThat(row.state()).isEqualTo(FileState.PENDING);
        }
    }

    @Test
    void aLateWinnerRepairsARowAnotherWriterTookOver() {
        // simulate the interleaving: B took over A's unwritten PENDING row, then A's file lands
        assertThatThrownBy(() -> crashingStorage().put(key, b("A-bytes"))).isInstanceOf(IllegalStateException.class);
        assertThat(registry.register(key, dir.resolve("x").toUri(), b("B-bytes-longer"), false))
                .isEqualTo(FileRegistry.Registration.REPLACED_UNWRITTEN); // the row now describes B
        // A links its file and then syncs: the row must describe A's bytes, because A's bytes are in the file
        new LocalFileStorage(dir, registry) {
            @Override protected void afterRegistered(String k) {}
        };
        registry.syncPending(key, dir.resolve("x").toUri(), b("A-bytes"));
        assertThat(registry.find(key).orElseThrow().size()).isEqualTo(7);
    }
}
