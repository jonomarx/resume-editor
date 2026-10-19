package app.resume.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Chooses the FileStorage implementation from FILE_STORAGE (application.yml: app.storage.type). */
@Configuration
public class StorageConfig {
    @Bean
    FileRegistry fileRegistry(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        return new FileRegistry(jdbc, txManager);
    }

    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
    FileStorage localFileStorage(@Value("${app.storage.data-dir}") String dataDir, FileRegistry registry) {
        return new LocalFileStorage(Path.of(dataDir), registry);
    }
}
