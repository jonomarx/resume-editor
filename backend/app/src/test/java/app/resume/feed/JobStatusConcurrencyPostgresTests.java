package app.resume.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.resume.CsrfSupport;
import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Racing first writes of one posting's status (primary key on user and posting) all succeed and leave one row. */
@PostgresTest
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "dummy", inheritProfiles = true)
class JobStatusConcurrencyPostgresTests extends PostgresContainerSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentFirstStatusWritesLeaveOneRow() throws Exception {
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"statusracer\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        String me = mvc.perform(get("/api/me").cookie(s)).andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(me.replaceAll(".*\"id\":(\\d+).*", "$1"));
        int n = 8;
        String[] statuses = {"saved", "dismissed", "applied", "saved", "dismissed", "applied", "saved", "dismissed"};
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String st = statuses[i];
            fs.add(pool.submit(() -> {
                go.await();
                return mvc.perform(CsrfSupport.put("/api/jobs/1/status").cookie(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + st + "\"}")).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : fs) assertThat(f.get()).isEqualTo(200);
        pool.shutdown();
        assertThat(jdbc.queryForObject("select count(*) from job_user_status where user_id = ? and posting_id = 1",
                Integer.class, userId)).isEqualTo(1);
    }
}
