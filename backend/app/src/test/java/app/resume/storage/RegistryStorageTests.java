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
}
