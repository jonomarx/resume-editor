package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Simultaneous creates of the same tag, with different spellings, leave exactly one row. */
@PostgresTest
@AutoConfigureMockMvc
class TagConcurrencyPostgresTests extends PostgresContainerSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentCreateReturnsOneRow() throws Exception {
        Cookie session = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tagracer\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
        String[] spellings = {"Rust", "rust", " RUST ", "Rust"};
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = spellings[i % spellings.length];
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(CsrfSupport.post("/api/tags").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"category\":\"language\"}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        int created = 0;
        int existing = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) created++;
            else if (s == 200) existing++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(existing).isEqualTo(n - 1);
        assertThat(jdbc.queryForObject("select count(*) from tag where slug = 'rust'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tag_key where tag_key = 'rust'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentAliasAddsOfTheSameAliasToTwoTagsHaveOneWinner() throws Exception {
        Cookie session = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aliasracer\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
        for (String name : new String[] {"Alpha", "Beta"}) {
            mvc.perform(CsrfSupport.post("/api/tags").cookie(session).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"category\":\"domain\"}")).andReturn();
        }
        int n = 12;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String slug = i % 2 == 0 ? "alpha" : "beta";
            String alias = i % 2 == 0 ? "Shared C++" : "SHARED c++"; // same alias key, different spelling
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(CsrfSupport.post("/api/tags/" + slug + "/aliases").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias + "\"}"))
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
        // one tag wins the alias: its 6 requests are 200 (one insert plus no-ops); the other tag's 6 are conflicts
        assertThat(ok).isEqualTo(n / 2);
        assertThat(conflict).isEqualTo(n / 2);
        assertThat(jdbc.queryForObject("select count(*) from tag_key where tag_key = 'shared c++'", Integer.class)).isEqualTo(1);
    }
}
