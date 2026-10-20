package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.web.servlet.MockMvc;

/** Real Postgres and real Redis: sessions live in Redis, and the unique constraint decides duplicate sign-ups. */
@PostgresTest
@AutoConfigureMockMvc
class RedisSessionPostgresTests extends PostgresContainerSupport {
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired SessionRepository<?> sessions;

    private static String creds(String u) {
        return "{\"username\":\"" + u + "\",\"password\":\"correct-horse-battery\"}";
    }

    @Test
    void sessionIsStoredInRedisAndRemovedOnLogout() throws Exception {
        assertThat(sessions).isInstanceOf(RedisSessionRepository.class);
        Set<String> before = redis.keys("spring:session:sessions:*");
        var res = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(creds("redisuser")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String setCookie = res.getHeaders("Set-Cookie").stream().filter(h -> h.startsWith("SESSION=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax");
        Cookie cookie = res.getCookie("SESSION");

        // other tests share this Redis: only this test's own session key counts
        Set<String> keys = new java.util.HashSet<>(redis.keys("spring:session:sessions:*"));
        keys.removeAll(before);
        assertThat(keys).hasSize(1);
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk());

        mvc.perform(CsrfSupport.post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
        assertThat(redis.keys("spring:session:sessions:*")).doesNotContainAnyElementsOf(keys);
    }

    @Test
    void simultaneousSignUpsOfTheSameUsernameCreateExactlyOneAccount() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<Integer> task = () -> {
                go.await();
                return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(creds("racer")))
                        .andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(task));
        }
        go.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) created++;
            else if (s == 409) conflicts++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(n - 1);
    }
}
