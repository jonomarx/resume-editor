package app.resume.jobs.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DomainRateLimiterTests {
    final AtomicLong now = new AtomicLong(1_000);
    final List<Long> sleeps = new ArrayList<>();
    final DomainRateLimiter limiter = new DomainRateLimiter(500, now::get, ms -> {
        sleeps.add(ms);
        now.addAndGet(ms);
    });

    @Test
    void secondRequestToSameHostWaitsForTheInterval() {
        limiter.acquire("a.example");
        now.addAndGet(100);
        limiter.acquire("a.example");
        assertThat(sleeps).containsExactly(400L);
    }

    @Test
    void otherHostsAreIndependentAndLaterRequestsDoNotWait() {
        limiter.acquire("a.example");
        limiter.acquire("b.example");
        now.addAndGet(600);
        limiter.acquire("a.example");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void burstsQueueUpInSlots() {
        limiter.acquire("a.example");
        limiter.acquire("a.example");
        limiter.acquire("a.example");
        assertThat(sleeps).containsExactly(500L, 500L);
    }
}
