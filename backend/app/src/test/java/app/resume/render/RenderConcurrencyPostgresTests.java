package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.CsrfSupport;
import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Identical renders in parallel compile once; the cache entry is complete and the rest are hits. Real Postgres and Redis. */
@PostgresTest
@AutoConfigureMockMvc
@Import(RenderConcurrencyPostgresTests.SlowCountingRenderer.class)
class RenderConcurrencyPostgresTests extends PostgresContainerSupport {
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class SlowCountingRenderer {
        @Bean
        @Primary
        RendererClient slowRenderer() {
            return tex -> {
                CALLS.incrementAndGet();
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new RendererClient.Pdf("%PDF-concurrent".getBytes(StandardCharsets.UTF_8), 1, "log");
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired StarterTree starter;
    @Autowired ObjectMapper mapper;

    @Test
    void identicalConcurrentRendersCompileOnce() throws Exception {
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"renderracer\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        String body = mapper.writeValueAsString(java.util.Map.of("tree", starter.create()));
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                var res = mvc.perform(CsrfSupport.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse();
                return res.getStatus() + ":" + mapper.readTree(res.getContentAsString()).get("cached").asBoolean();
            }));
        }
        go.countDown();
        int misses = 0;
        int hits = 0;
        for (Future<String> f : fs) {
            String r = f.get();
            if (r.equals("200:false")) misses++; else if (r.equals("200:true")) hits++;
        }
        pool.shutdown();
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(misses).isEqualTo(1);
        assertThat(hits).isEqualTo(n - 1);
    }
}
