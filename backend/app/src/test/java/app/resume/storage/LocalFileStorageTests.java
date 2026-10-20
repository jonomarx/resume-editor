package app.resume.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LocalFileStorageTests {
    static final AtomicLong USERS = new AtomicLong(1000);

    @TempDir Path dir;
    @Autowired FileRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    LocalFileStorage storage;
    long uid;
    String key;

    @BeforeEach
    void setUp() {
        uid = USERS.incrementAndGet(); // the registry is shared by every test in the context: keep keys apart
        key = StorageKeys.resumeVersion(uid, 42, 1);
        storage = new LocalFileStorage(dir, registry);
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void putThenGetReturnsTheBytesAndAFileUriAtTheDesignedPath() throws Exception {
        URI uri = storage.put(key, b("{\"v\":1}"));
        assertThat(uri.getScheme()).isEqualTo("file");
        assertThat(Path.of(uri)).isEqualTo(dir.toRealPath().resolve("users/" + uid + "/resumes/42/v1.json").toAbsolutePath().normalize());
        assertThat(Files.readString(Path.of(uri))).isEqualTo("{\"v\":1}");
        assertThat(storage.get(key)).hasValueSatisfying(v -> assertThat(v).isEqualTo(b("{\"v\":1}")));
    }

    @Test
    void existsAndGetReflectPresence() {
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.get(key)).isEmpty();
        storage.put(key, b("x"));
        assertThat(storage.exists(key)).isTrue();
    }

    @Test
    void deleteRemovesTheFileAndReportsWhetherThereWasOne() {
        storage.put(key, b("x"));
        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void versionsAreImmutable() {
        storage.put(key, b("first"));
        assertThatThrownBy(() -> storage.put(key, b("second"))).isInstanceOf(KeyAlreadyExistsException.class);
        assertThat(storage.get(key).orElseThrow()).isEqualTo(b("first"));
    }

    @Test
    void puttingIdenticalBytesAgainIsASafeRetry() {
        URI first = storage.put(key, b("same"));
        assertThat(storage.put(key, b("same"))).isEqualTo(first);
    }

    @Test
    void noTempFilesRemainAfterSuccessOrConflict() throws Exception {
        storage.put(key, b("a"));
        assertThatThrownBy(() -> storage.put(key, b("b"))).isInstanceOf(KeyAlreadyExistsException.class);
        assertThat(storage.tempFilesIn(key)).isZero();
        try (var files = Files.list(dir.resolve("users/" + uid + "/resumes/42"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("v1.json");
        }
    }

    @Test
    void unsafeKeysAreRejectedBeforeTouchingDisk() {
        for (String bad : new String[] {"", "../evil", "a/../../evil", "/etc/passwd", "a//b", "a/./b", "..", ".hidden",
                "a\\b", "a/b/", "users/1/resumes/2/..v1", "x".repeat(200)}) {
            assertThatThrownBy(() -> storage.put(bad, b("x"))).as(bad).isInstanceOf(InvalidStorageKeyException.class);
            assertThatThrownBy(() -> storage.get(bad)).as(bad).isInstanceOf(InvalidStorageKeyException.class);
        }
        assertThat(dir).isEmptyDirectory();
        assertThatThrownBy(() -> storage.list("../x/", FileState.COMMITTED)).isInstanceOf(InvalidStorageKeyException.class);
        assertThatThrownBy(() -> storage.list("", FileState.COMMITTED)).isInstanceOf(InvalidStorageKeyException.class);
    }

    @Test
    void emptyContentIsAValidFile() {
        storage.put(key, new byte[0]);
        assertThat(storage.get(key).orElseThrow()).isEmpty();
    }

    @Test
    void keyHelpersBuildTheDesignedLayout() {
        assertThat(StorageKeys.resumeVersion(1, 2, 3)).isEqualTo("users/1/resumes/2/v3.json");
        assertThat(StorageKeys.resumeDirectory(1, 2)).isEqualTo("users/1/resumes/2");
        assertThatThrownBy(() -> StorageKeys.resumeVersion(1, 2, 0)).isInstanceOf(InvalidStorageKeyException.class);
    }

    @Test
    void deleteRemovesTheRegistryRowToo() {
        storage.put(key, b("x"));
        assertThat(registry.find(key)).isPresent();
        assertThat(storage.delete(key)).isTrue();
        assertThat(registry.find(key)).isEmpty();
    }

    @Test
    void deleteOfARowWithoutAFileRemovesTheRowAndReturnsTrue() {
        storage.put(key, b("x"));
        Path file = Path.of(storage.put(key, b("x")));
        try { Files.delete(file); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        assertThat(storage.delete(key)).isTrue();
        assertThat(registry.find(key)).isEmpty();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void deleteRetriedAfterAFailedRowRemovalCompletesTheJob() {
        java.util.concurrent.atomic.AtomicBoolean failNext = new java.util.concurrent.atomic.AtomicBoolean(true);
        FileRegistry flaky = new FileRegistry(jdbc, txManager) {
            @Override public boolean removeIfUnchanged(Entry expected) {
                if (failNext.getAndSet(false)) throw new IllegalStateException("simulated row removal failure");
                return super.removeIfUnchanged(expected);
            }
        };
        LocalFileStorage s = new LocalFileStorage(dir, flaky);
        s.put(key, b("x"));
        assertThatThrownBy(() -> s.delete(key)).isInstanceOf(IllegalStateException.class);
        assertThat(s.exists(key)).as("the file was removed first").isFalse();
        assertThat(flaky.find(key)).as("the row is left describing a missing file").isPresent();
        assertThat(s.delete(key)).isTrue(); // idempotent: finishes the job
        assertThat(flaky.find(key)).isEmpty();
        assertThat(s.delete(key)).isFalse();
    }

    @Test
    void aRowChangedAfterItWasReadIsNotRemoved() {
        storage.put(key, b("x"));
        FileRegistry.Entry stale = registry.find(key).orElseThrow();
        jdbc.update("update file_registry set state = 'COMMITTED' where file_key = ?", key); // another process moved on
        assertThat(registry.removeIfUnchanged(stale)).isFalse();
        assertThat(registry.find(key)).isPresent();
        assertThat(registry.removeIfUnchanged(registry.find(key).orElseThrow())).isTrue();
    }
}
