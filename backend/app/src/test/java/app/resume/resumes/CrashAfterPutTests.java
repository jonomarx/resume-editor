package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resume.render.TreeHasher;
import app.resume.storage.FileState;
import app.resume.storage.FileStorage;
import app.resume.storage.LocalFileStorage;
import app.resume.storage.FileRegistry;
import app.resume.storage.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** A save that dies after the file is written (before the row commits) leaves a PENDING file and no committed version. */
@SpringBootTest
@ActiveProfiles("test")
@Import(CrashAfterPutTests.Flaky.class)
class CrashAfterPutTests {
    static final AtomicBoolean FAIL_COMMIT = new AtomicBoolean();

    @TestConfiguration
    static class Flaky {
        @Bean
        @Primary
        FileStorage flakyStorage(FileRegistry registry, @org.springframework.beans.factory.annotation.Value("${app.storage.data-dir}") String dir) {
            LocalFileStorage real = new LocalFileStorage(Path.of(dir), registry);
            return new FileStorage() {
                public URI put(String key, byte[] bytes) { return real.put(key, bytes); }
                public void commit(String key) {
                    if (FAIL_COMMIT.get()) throw new IllegalStateException("simulated crash after put");
                    real.commit(key);
                }
                public Optional<byte[]> get(String key) { return real.get(key); }
                public boolean delete(String key) { return real.delete(key); }
                public boolean exists(String key) { return real.exists(key); }
                public List<StoredFile> list(String prefix, FileState state) { return real.list(prefix, state); }
            };
        }
    }

    @Autowired ResumeService service;
    @Autowired FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired app.resume.auth.AppUserRepository users;

    long newUserId() {
        return users.saveAndFlush(new app.resume.auth.AppUser("crash" + System.nanoTime(), "x")).getId();
    }

    JsonNode edited(JsonNode tree, String latex) {
        ObjectNode copy = tree.deepCopy();
        ((ObjectNode) copy.get("sections").get(1).get("children").get(0).get("children").get(0)).put("latex", latex);
        return copy;
    }

    @Test
    void aCrashAfterThePutLeavesAPendingFileAndNoNewVersionAndRetriesAreSafe() {
        long uid = newUserId();
        ResumeService.Doc v1 = service.getMaster(uid);
        JsonNode tree = mapper.valueToTree(v1.tree());
        String prefix = "users/" + uid + "/resumes/" + v1.id() + "/";

        FAIL_COMMIT.set(true);
        assertThatThrownBy(() -> service.save(uid, 1, edited(tree, "\\resumeItem{first try}"))).hasMessageContaining("simulated crash");
        FAIL_COMMIT.set(false);

        // the row transaction rolled back: still version 1, no version 2 row
        assertThat(service.getMaster(uid).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from resume_version where resume_id = ?", Integer.class, v1.id())).isEqualTo(1);
        // the file was written first and is a PENDING orphan, as designed
        assertThat(storage.list(prefix, FileState.PENDING)).extracting(StoredFile::key).containsExactly(prefix + "v2.json");

        // retrying the same content reuses the identical file and commits it
        ResumeService.Doc retried = service.save(uid, 1, edited(tree, "\\resumeItem{first try}"));
        assertThat(retried.version()).isEqualTo(2);
        assertThat(storage.list(prefix, FileState.PENDING)).isEmpty();
        assertThat(jdbc.queryForObject("select file_key from resume_version where resume_id = ? and version = 2", String.class, v1.id()))
                .isEqualTo(prefix + "v2.json");
    }

    @Test
    void aRetryWithDifferentContentNeverReusesTheOrphanKey() {
        long uid = newUserId();
        ResumeService.Doc v1 = service.getMaster(uid);
        JsonNode tree = mapper.valueToTree(v1.tree());
        String prefix = "users/" + uid + "/resumes/" + v1.id() + "/";

        FAIL_COMMIT.set(true);
        assertThatThrownBy(() -> service.save(uid, 1, edited(tree, "\\resumeItem{orphan}"))).isInstanceOf(IllegalStateException.class);
        FAIL_COMMIT.set(false);

        ResumeService.Doc saved = service.save(uid, 1, edited(tree, "\\resumeItem{different}"));
        assertThat(saved.version()).isEqualTo(2);
        String key = jdbc.queryForObject("select file_key from resume_version where resume_id = ? and version = 2", String.class, v1.id());
        assertThat(key).isEqualTo(prefix + "v2-r1.json"); // v2.json is the unreferenced orphan with other bytes
        assertThat(storage.list(prefix, FileState.PENDING)).extracting(StoredFile::key).containsExactly(prefix + "v2.json");
        assertThat(service.getMaster(uid).contentHash()).isEqualTo(TreeHasher.hash(saved.tree()));
        assertThat(service.getMaster(uid).tree().sections().get(1).children().get(0).children().get(0).latex()).isEqualTo("\\resumeItem{different}");
    }
}
