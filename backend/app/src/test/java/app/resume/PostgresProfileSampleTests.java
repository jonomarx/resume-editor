package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves the postgres profile runs tagged tests against real Postgres with migrations applied. */
@PostgresTest
class PostgresProfileSampleTests extends PostgresContainerSupport {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void runsOnRealPostgresWithFlywayApplied() {
        String version = jdbc.queryForObject("select version()", String.class);
        assertThat(version).startsWith("PostgreSQL 16");
        Integer migrations = jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(1);
    }
}
