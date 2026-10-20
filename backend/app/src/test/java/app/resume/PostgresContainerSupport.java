package app.resume;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/** One shared Postgres and Redis container per JVM; their connections replace the datasource and Redis settings. */
public abstract class PostgresContainerSupport {
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }
}
