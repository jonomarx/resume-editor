package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resume.storage.ResumeLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The lock-scoped unit of work: transaction inside the lock, commit before release, release on exception. */
@SpringBootTest
@ActiveProfiles("test")
class ResumeLocksTests {
    @Autowired ResumeLocks locks;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void table() {
        jdbc.execute("create table if not exists lock_probe (id bigint primary key, v bigint not null)");
        jdbc.update("delete from lock_probe");
        jdbc.update("insert into lock_probe (id, v) values (1, 0)");
    }

    long read() { return jdbc.queryForObject("select v from lock_probe where id = 1", Long.class); }

    @Test
    void anExceptionRollsBackTheWorkAndReleasesTheLock() {
        assertThatThrownBy(() -> locks.withResumeLock(11L, () -> {
            jdbc.update("update lock_probe set v = 99 where id = 1");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(read()).isZero(); // rolled back
        assertThat(locks.withResumeLock(11L, () -> "reacquired")).isEqualTo("reacquired"); // lock was released
    }

    @Test
    void theLockCoversTheCommitSoASecondWriterNeverReadsTheOldValue() throws Exception {
        CountDownLatch insideA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> locks.withResumeLock(12L, () -> {
            jdbc.update("update lock_probe set v = 1 where id = 1");
            insideA.countDown();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        insideA.await();
        Future<Long> b = pool.submit(() -> locks.withResumeLock(12L, () -> read())); // starts while A still holds the lock
        assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(1L); // B waited for A's commit: never the old value
        a.get();
        pool.shutdown();
    }

    @Test
    void parallelReadModifyWriteWritersLoseNoUpdates() throws Exception {
        int threads = 12;
        int each = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                for (int k = 0; k < each; k++) {
                    locks.withResumeLock(13L, () -> jdbc.update("update lock_probe set v = ? where id = 1", read() + 1));
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(read()).isEqualTo((long) threads * each);
    }

    @Test
    void resultsAreReturnedAfterTheCommitAndLocksOfDifferentResumesAreIndependent() throws Exception {
        assertThat(locks.withResumeLock(14L, () -> { jdbc.update("update lock_probe set v = 5 where id = 1"); return 42; })).isEqualTo(42);
        assertThat(read()).isEqualTo(5);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> locks.withResumeLock(15L, () -> {
            holding.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        holding.await();
        assertThat(pool.submit(() -> locks.withResumeLock(16L, () -> "ok")).get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        release.countDown();
        holder.get();
        pool.shutdown();
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void callingItInsideAnActiveTransactionFailsFastAndLeavesTheLockFree() throws Exception {
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> locks.withResumeLock(21L, () -> jdbc.update("update lock_probe set v = 7 where id = 1"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction").hasMessageContaining("cover the commit");
        assertThat(read()).isZero(); // the work never ran
        // the lock was not taken, so another thread (and this one) can acquire it at once
        ExecutorService pool = Executors.newSingleThreadExecutor();
        assertThat(pool.submit(() -> locks.withResumeLock(21L, () -> "free")).get(5, TimeUnit.SECONDS)).isEqualTo("free");
        pool.shutdown();
        assertThat(locks.withResumeLock(21L, () -> "free again")).isEqualTo("free again");
    }
}
