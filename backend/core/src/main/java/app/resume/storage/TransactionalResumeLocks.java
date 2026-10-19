package app.resume.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ResumeLocks}: an in-process lock per resume id (outermost, so it is released only after the commit) around a
 * transaction that, on Postgres, first takes `pg_advisory_xact_lock` for the resume (so other processes wait too, until
 * this transaction commits or rolls back). H2 has no advisory locks; the in-process lock covers single-JVM tests.
 */
@Component
public class TransactionalResumeLocks implements ResumeLocks {
    /** Keeps resume advisory locks apart from any other advisory lock use; resume ids stay far below 2^40. */
    private static final long ADVISORY_NAMESPACE = 0x5245_5300_0000_0000L;

    private final KeyedLocks<Long> locks = new KeyedLocks<>();
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public TransactionalResumeLocks(PlatformTransactionManager txManager, JdbcTemplate jdbc, DataSource dataSource) {
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.postgres = isPostgres(dataSource);
    }

    private static boolean isPostgres(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public <T> T withResumeLock(long resumeId, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Joining the caller's transaction would let the lock be released before that transaction commits, which defeats it.
            throw new IllegalStateException("withResumeLock must not be called inside an active transaction: the lock has to"
                    + " cover the commit, so it starts its own transaction. Call it from non-transactional code.");
        }
        return locks.call(resumeId, () -> tx.execute(status -> {
            if (postgres) jdbc.query("select pg_advisory_xact_lock(?)", rs -> {}, ADVISORY_NAMESPACE | resumeId);
            return work.get();
        }));
    }
}
