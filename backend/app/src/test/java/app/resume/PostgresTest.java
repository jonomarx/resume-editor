package app.resume;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Marks a Spring test that needs real Postgres (Postgres-only features and all concurrency tests).
 * Excluded from {@code mvn test}; run by {@code mvn verify -Ppostgres}. Extend {@link PostgresContainerSupport}
 * (or declare your own {@code @ServiceConnection} container) so the datasource points at Testcontainers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("postgres")
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres-test")
public @interface PostgresTest {
}
