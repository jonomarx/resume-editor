package app.resume.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KeyedLocksTests {
    @Test
    void entriesAreFreedAfterNormalReturnAndAfterAnException() {
        KeyedLocks<String> locks = new KeyedLocks<>();
        locks.run("a", () -> {});
        assertThatThrownBy(() -> locks.run("b", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(locks.size()).isZero();
    }

    @Test
    void differentKeysRunInParallelAndTheMapIsEmptyAfterwards() throws Exception {
        KeyedLocks<String> locks = new KeyedLocks<>();
        CountDownLatch bothInside = new CountDownLatch(2);
        AtomicInteger finished = new AtomicInteger();
        Runnable a = () -> locks.run("a", () -> await(bothInside, finished));
        Runnable b = () -> locks.run("b", () -> await(bothInside, finished));
        Thread ta = new Thread(a), tb = new Thread(b);
        ta.start();
        tb.start();
        ta.join(5000);
        tb.join(5000);
        assertThat(finished.get()).as("both keys were inside at the same time").isEqualTo(2);
        assertThat(locks.size()).isZero();
    }

    @Test
    void theSameKeyIsMutuallyExclusive() throws Exception {
        KeyedLocks<String> locks = new KeyedLocks<>();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> locks.run("k", () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                inside.decrementAndGet();
            }));
            threads[i].start();
        }
        for (Thread t : threads) t.join(5000);
        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(locks.size()).isZero();
    }

    private static void await(CountDownLatch latch, AtomicInteger finished) {
        latch.countDown();
        try {
            if (latch.await(3, TimeUnit.SECONDS)) finished.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
