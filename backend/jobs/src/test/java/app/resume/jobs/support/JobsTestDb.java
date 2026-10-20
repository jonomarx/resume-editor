package app.resume.jobs.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** A fresh H2 (PostgreSQL mode) database with the jobs migrations on top of stub copies of core's tables. */
public final class JobsTestDb {
    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final TransactionTemplate tx;

    public JobsTestDb() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:jobs" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/stub", "classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public long user(String name) {
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES (?, ?, 'x')", name, name.toLowerCase());
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username_key = ?", Long.class, name.toLowerCase());
    }
}
