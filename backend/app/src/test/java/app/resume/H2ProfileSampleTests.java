package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Proves the default (fast) loop runs on H2 in PostgreSQL mode. */
@SpringBootTest
@ActiveProfiles("test")
class H2ProfileSampleTests {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void runsOnH2() {
        String url = jdbc.getDataSource() == null ? "" : jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:h2:");
    }
}
