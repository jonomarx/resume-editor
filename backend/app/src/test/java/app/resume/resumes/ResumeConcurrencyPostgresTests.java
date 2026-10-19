package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.resume.CsrfSupport;
import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
import app.resume.storage.FileState;
import app.resume.storage.FileStorage;
import app.resume.storage.TransactionalResumeLocks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** Real Postgres: simultaneous saves, and the resume lock across separate lock instances (standing in for processes). */
@PostgresTest
@AutoConfigureMockMvc
class ResumeConcurrencyPostgresTests extends PostgresContainerSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage storage;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @Test
    void simultaneousSavesWithTheSameBaseVersionHaveExactlyOneWinner() throws Exception {
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"saveracer\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        JsonNode doc = mapper.readTree(mvc.perform(get("/api/resumes/master").cookie(s)).andReturn().getResponse().getContentAsString());
        long resumeId = doc.get("id").asLong();
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ObjectNode tree = doc.get("tree").deepCopy();
            ((ObjectNode) tree.get("sections").get(1).get("children").get(0).get("children").get(0)).put("latex", "\\resumeItem{writer " + i + "}");
            ObjectNode body = mapper.createObjectNode().put("baseVersion", 1);
            body.set("tree", tree);
            String json = mapper.writeValueAsString(body);
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON).content(json))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int st = f.get();
            if (st == 200) ok++; else if (st == 409) conflict++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(n - 1);
        assertThat(jdbc.queryForObject("select count(*) from resume_version where resume_id = ?", Integer.class, resumeId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select version from resume where id = ?", Long.class, resumeId)).isEqualTo(2L);
        long uid = jdbc.queryForObject("select user_id from resume where id = ?", Long.class, resumeId);
        String prefix = "users/" + uid + "/resumes/" + resumeId + "/";
        // (other test classes share this database and also use user 1 / resume 1 keys, so look only at this save's files)
        assertThat(storage.list(prefix + "v", FileState.COMMITTED)).extracting(f -> f.key().substring(prefix.length()))
                .contains("v1.json", "v2.json");
        assertThat(storage.list(prefix + "v2", FileState.PENDING)).isEmpty(); // losers failed before writing anything
        assertThat(storage.list(prefix + "v3", FileState.PENDING)).isEmpty();
    }

    @Test
    void theResumeLockHoldsAcrossSeparateLockInstancesSoNoUpdateIsLost() throws Exception {
        jdbc.execute("create table if not exists lock_probe (id bigint primary key, v bigint not null)");
        jdbc.update("delete from lock_probe");
        jdbc.update("insert into lock_probe (id, v) values (1, 0)");
        // two instances have separate in-process locks, like two app processes: only the Postgres advisory lock serializes them
        TransactionalResumeLocks a = new TransactionalResumeLocks(txManager, jdbc, dataSource);
        TransactionalResumeLocks b = new TransactionalResumeLocks(txManager, jdbc, dataSource);
        int threads = 8;
        int each = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            TransactionalResumeLocks locks = i % 2 == 0 ? a : b;
            fs.add(pool.submit(() -> {
                for (int k = 0; k < each; k++) {
                    locks.withResumeLock(777L, () -> {
                        long v = jdbc.queryForObject("select v from lock_probe where id = 1", Long.class);
                        jdbc.update("update lock_probe set v = ? where id = 1", v + 1);
                    });
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(jdbc.queryForObject("select v from lock_probe where id = 1", Long.class)).isEqualTo((long) threads * each);
    }
}
