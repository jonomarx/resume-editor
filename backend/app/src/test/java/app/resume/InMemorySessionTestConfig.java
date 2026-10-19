package app.resume;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

import java.util.concurrent.ConcurrentHashMap;

/** Fast-loop tests keep sessions in memory so they need no Redis. The postgres-tagged test uses real Redis. */
@Configuration
@EnableSpringHttpSession
@Profile("test")
class InMemorySessionTestConfig {
    @Bean
    SessionRepository<MapSession> sessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }
}
