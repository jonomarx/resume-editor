package app.resume.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the FileStorage implementation from FILE_STORAGE (application.yml: app.storage.type). */
@Configuration
public class StorageConfig {
    @Bean
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
    FileStorage localFileStorage(@Value("${app.storage.data-dir}") String dataDir) {
        return new LocalFileStorage(Path.of(dataDir));
    }
}
