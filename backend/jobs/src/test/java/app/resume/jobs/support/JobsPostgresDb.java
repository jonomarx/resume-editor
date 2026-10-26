package app.resume.jobs.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** A shared Postgres 16 container (Docker) with the jobs migrations on stub copies of core's tables. */
public final class JobsPostgresDb {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    private static boolean started;

    public final JdbcTemplate jdbc;

    public JobsPostgresDb() {
        synchronized (JobsPostgresDb.class) {
            if (!started) {
                POSTGRES.start();
                started = true;
            }
        }
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).cleanDisabled(false).locations("classpath:db/stub", "classpath:db/migration").load().clean();
        Flyway.configure().dataSource(ds).locations("classpath:db/stub", "classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    public long user(String name) {
        jdbc.update("INSERT INTO app_user (username, username_key, password_hash) VALUES (?, ?, 'x')", name, name.toLowerCase());
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username_key = ?", Long.class, name.toLowerCase());
    }
}
