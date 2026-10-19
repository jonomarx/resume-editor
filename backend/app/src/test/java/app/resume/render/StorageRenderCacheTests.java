package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.LatexAssembler;
import app.resume.storage.FileRegistry;
import app.resume.storage.FileState;
import app.resume.storage.FileStorage;
import app.resume.storage.LocalFileStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** The render cache on FileStorage: per-user keys, atomic, template-aware, entries committed in the registry. */
@SpringBootTest
@ActiveProfiles("test")
class StorageRenderCacheTests {
    static final AtomicLong USERS = new AtomicLong(70000);

    @TempDir Path dir;
    @Autowired FileRegistry registry;
    @Autowired PlatformTransactionManager txManager;
    @Autowired ObjectMapper mapper;
    FileStorage storage;
    StorageRenderCache cache;
    long uid;
    final String hash = "c".repeat(64);

    @BeforeEach
    void setUp() {
        uid = USERS.incrementAndGet();
        storage = new LocalFileStorage(dir, registry);
        cache = new StorageRenderCache(storage, txManager, new LatexAssembler(), mapper);
    }

    String fp() { return new LatexAssembler().template().fingerprint(); }

    @Test
    void storeThenFindAndServeThePdfPerUser() {
        cache.store(uid, hash, fp(), "%PDF-1".getBytes(), 2, "the log");
        assertThat(cache.find(uid, hash, fp())).hasValueSatisfying(e -> {
            assertThat(e.pageCount()).isEqualTo(2);
            assertThat(e.log()).isEqualTo("the log");
        });
        assertThat(new String(cache.pdf(uid, hash).orElseThrow())).isEqualTo("%PDF-1");
        assertThat(cache.find(uid + 1, hash, fp())).isEmpty(); // another user
        assertThat(cache.pdf(uid + 1, hash)).isEmpty();
    }

    @Test
    void anEntryFromAnotherTemplateIsAMissAndThePdfRouteUsesTheCurrentTemplate() {
        cache.store(uid, hash, "oldtemplate", "%PDF-old".getBytes(), 1, "");
        assertThat(cache.find(uid, hash, fp())).isEmpty();
        assertThat(cache.pdf(uid, hash)).isEmpty(); // GET /pdf no longer serves a PDF from a stale template
        cache.store(uid, hash, fp(), "%PDF-new".getBytes(), 1, "");
        assertThat(new String(cache.pdf(uid, hash).orElseThrow())).isEqualTo("%PDF-new");
    }

    @Test
    void entriesAreCommittedInTheRegistryUnderTheUsersRenderPrefix() {
        cache.store(uid, hash, fp(), "%PDF".getBytes(), 1, "");
        var committed = storage.list("users/" + uid + "/render/", FileState.COMMITTED);
        assertThat(committed).singleElement().satisfies(f -> assertThat(f.key()).endsWith(hash + "-" + fp() + ".entry"));
        assertThat(storage.list("users/" + uid + "/render/", FileState.PENDING)).isEmpty();
    }

    @Test
    void aSecondStoreOfTheSameKeyWithDifferentBytesIsHarmless() {
        cache.store(uid, hash, fp(), "%PDF-first".getBytes(), 1, "a");
        cache.store(uid, hash, fp(), "%PDF-second-run-differs".getBytes(), 1, "b"); // pdfTeX output differs between runs
        assertThat(cache.find(uid, hash, fp())).isPresent();
        assertThat(storage.list("users/" + uid + "/render/", FileState.COMMITTED)).hasSize(1);
    }

    @Test
    void readersNeverSeeAPartialEntryWhileItIsWritten() throws Exception {
        byte[] big = new byte[12_000_000];
        java.util.Arrays.fill(big, (byte) 'B');
        var stop = new AtomicBoolean();
        var bad = new AtomicInteger();
        var seen = new AtomicInteger();
        Thread reader = new Thread(() -> {
            while (!stop.get()) {
                cache.pdf(uid, hash).ifPresent(b -> {
                    seen.incrementAndGet();
                    if (b.length != big.length) bad.incrementAndGet();
                });
            }
        });
        reader.start();
        cache.store(uid, hash, fp(), big, 1, "l");
        // keep reading a little after the write so the reader certainly observes the finished entry
        long until = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < until) Thread.onSpinWait();
        stop.set(true);
        reader.join();
        assertThat(seen.get()).isPositive();
        assertThat(bad.get()).isZero();
    }

    @Test
    void aMalformedHashIsRejectedAndNeverTouchesStorage() {
        assertThat(cache.pdf(uid, "../../x")).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.find(uid, "nothex", fp()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
