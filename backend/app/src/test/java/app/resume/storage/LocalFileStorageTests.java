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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LocalFileStorageTests {
    static final AtomicLong USERS = new AtomicLong(1000);

    @TempDir Path dir;
    @Autowired FileRegistry registry;
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
    void lockEntriesAreReleasedWhenUnused() {
        KeyedLocks<String> locks = new KeyedLocks<>();
        locks.run("a", () -> {});
        locks.run("b", () -> { throw_(); });
        assertThat(locks.size()).isZero();
    }

    private static void throw_() { /* no-op: lock freed even after normal return */ }
}
